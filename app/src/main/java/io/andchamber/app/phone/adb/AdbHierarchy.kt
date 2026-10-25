// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneNode
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.core.PhoneSnapshot
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** A parsed accessibility node, before the driver binds it to the current display. */
class AdbNodeFact(
    val packageName: String,
    val bounds: PhoneRect,
    val role: String,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
)

class AdbHierarchy(
    val rotation: Int,
    val packageName: String,
    val nodes: List<AdbNodeFact>,
)

class AdbHierarchyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Safe parser for `uiautomator dump /dev/tty`.
 *
 * The input is untrusted page content. The parser bounds the byte size and node count, rejects any
 * DOCTYPE/ENTITY declaration before handing the text to a parser (so external or recursive entities
 * never run), and never logs labels. It is not a general XML parser.
 */
object AdbHierarchyParser {
    const val MAX_XML_BYTES: Int = 2 * 1024 * 1024
    const val MAX_RAW_NODES: Int = 2_000

    fun parse(bytes: ByteArray): AdbHierarchy {
        if (bytes.isEmpty()) throw AdbHierarchyException("empty hierarchy")
        if (bytes.size > MAX_XML_BYTES) throw AdbHierarchyException("hierarchy too large")

        val text = String(bytes, Charsets.UTF_8)
        if (text.indexOf('\u0000') >= 0) throw AdbHierarchyException("hierarchy contains NUL")
        if (text.contains("<!DOCTYPE", ignoreCase = true) ||
            text.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw AdbHierarchyException("XML declarations are not accepted")
        }

        val start = text.indexOf("<hierarchy")
        if (start < 0) throw AdbHierarchyException("no hierarchy element")
        val closeTag = "</hierarchy>"
        val end = text.lastIndexOf(closeTag)
        if (end < start) throw AdbHierarchyException("unterminated hierarchy element")
        val xml = text.substring(start, end + closeTag.length)

        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            // Features are best effort: the DOCTYPE/ENTITY pre-scan above already blocks the
            // dangerous declarations on parsers that do not know these feature names.
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (_: StopParsing) {
            // The node cap is a normal truncation point.
        } catch (failure: SAXException) {
            throw AdbHierarchyException("malformed hierarchy", failure)
        } catch (failure: Exception) {
            throw AdbHierarchyException("hierarchy parse failed", failure)
        }

        val packageName = handler.packageName
            ?: throw AdbHierarchyException("no foreground package")
        if (packageName.any { it.isISOControl() } ||
            packageName.length > PhoneLimits.MAX_PACKAGE_CHARS
        ) {
            throw AdbHierarchyException("invalid foreground package")
        }
        return AdbHierarchy(
            rotation = handler.rotation,
            packageName = packageName,
            nodes = handler.nodes,
        )
    }

    private class Handler : DefaultHandler() {
        var rotation: Int = 0
        var packageName: String? = null
        val nodes = ArrayList<AdbNodeFact>()

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes,
        ) {
            when (qName) {
                "hierarchy" -> {
                    val value = attributes.getValue("rotation")
                    if (value != null) {
                        rotation = value.toIntOrNull()?.takeIf { it in 0..3 }
                            ?: throw AdbHierarchyException("invalid rotation")
                    }
                }
                "node" -> {
                    val packageValue = attributes.getValue("package").orEmpty()
                    if (packageName == null && packageValue.isNotBlank()) {
                        packageName = packageValue
                    }
                    val bounds = parseAdbBounds(attributes.getValue("bounds")) ?: return
                    if (bounds.width <= 0 || bounds.height <= 0) return
                    if (bounds.width > PhoneLimits.MAX_DISPLAY_PX ||
                        bounds.height > PhoneLimits.MAX_DISPLAY_PX
                    ) {
                        return
                    }
                    val className = attributes.getValue("class").orEmpty()
                    val role = className.substringAfterLast('.')
                        .ifBlank { "view" }
                        .take(PhoneLimits.MAX_ROLE_CHARS)
                    val editableClass =
                        className.endsWith("EditText") ||
                            className.endsWith("AutoCompleteTextView") ||
                            className.endsWith("TextInputEditText")
                    val password = attributes.getValue("password") == "true"
                    // Never expose a password value or an editable field's current value to the
                    // model. Editable/password nodes are identified by role and bounds only.
                    val label = if (editableClass || password) {
                        ""
                    } else {
                        (
                            attributes.getValue("text")?.takeIf { it.isNotBlank() }
                                ?: attributes.getValue("content-desc").orEmpty()
                            )
                            .filterNot { it.isISOControl() }
                            .take(PhoneLimits.MAX_NODE_LABEL_CHARS)
                    }
                    val editable = editableClass && !password
                    nodes.add(
                        AdbNodeFact(
                            packageName = packageValue.ifBlank { packageName.orEmpty() },
                            bounds = bounds,
                            role = role,
                            label = label,
                            clickable = attributes.getValue("clickable") == "true",
                            editable = editable,
                            focused = attributes.getValue("focused") == "true",
                        ),
                    )
                    if (nodes.size >= MAX_RAW_NODES) throw StopParsing()
                }
            }
        }
    }

    private class StopParsing : SAXException()
}

private val ADB_BOUNDS = Regex("^\\[(-?\\d{1,6}),(-?\\d{1,6})\\]\\[(-?\\d{1,6}),(-?\\d{1,6})\\]$")

private fun parseAdbBounds(value: String?): PhoneRect? {
    if (value == null) return null
    val match = ADB_BOUNDS.matchEntire(value) ?: return null
    return PhoneRect(
        left = match.groupValues[1].toInt(),
        top = match.groupValues[2].toInt(),
        right = match.groupValues[3].toInt(),
        bottom = match.groupValues[4].toInt(),
    )
}

/**
 * Binds parsed nodes to the live display geometry and to the real window-manager identity.
 * Nodes from another window (for example an IME window), invisible bounds, and anything outside the
 * display are dropped. References are assigned only after that filter, so a ref always maps to one
 * current bound. The window id is a digest, not raw dumpsys text.
 */
object AdbSnapshotBuilder {
    fun build(
        hierarchy: AdbHierarchy,
        geometry: PhoneGeometry,
        focus: AdbFocusedWindow,
        maxNodes: Int = PhoneLimits.MAX_NODES,
    ): PhoneSnapshot {
        val boundedNodes = ArrayList<PhoneNode>(minOf(maxNodes, hierarchy.nodes.size))
        for (fact in hierarchy.nodes) {
            if (boundedNodes.size >= maxNodes) break
            if (fact.packageName.isNotEmpty() && fact.packageName != hierarchy.packageName) continue
            if (!fact.bounds.isWithin(geometry)) continue
            val ref = "n${boundedNodes.size}"
            boundedNodes.add(
                PhoneNode(
                    ref = ref,
                    role = fact.role.take(PhoneLimits.MAX_ROLE_CHARS),
                    label = fact.label.take(PhoneLimits.MAX_NODE_LABEL_CHARS),
                    bounds = fact.bounds,
                    clickable = fact.clickable,
                    editable = fact.editable,
                    focused = fact.focused,
                ),
            )
        }
        return PhoneSnapshot(
            packageName = hierarchy.packageName,
            windowId = AdbWindowIdentity.witness(
                focus = focus,
                packageName = hierarchy.packageName,
                geometry = geometry,
                nodes = boundedNodes,
            ).encode(),
            geometry = geometry,
            nodes = boundedNodes,
            refs = boundedNodes.associate { it.ref to it.bounds },
        )
    }
}

/** Parses `wm size` and derives the display geometry for a rotation. */
object AdbDisplay {
    private val OVERRIDE = Regex("Override size: (\\d{1,5})x(\\d{1,5})")
    private val PHYSICAL = Regex("Physical size: (\\d{1,5})x(\\d{1,5})")

    fun parseSize(output: String): Pair<Int, Int>? {
        val match = OVERRIDE.findAll(output).lastOrNull()
            ?: PHYSICAL.findAll(output).lastOrNull()
            ?: return null
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        if (width !in 1..PhoneLimits.MAX_DISPLAY_PX ||
            height !in 1..PhoneLimits.MAX_DISPLAY_PX
        ) {
            return null
        }
        return width to height
    }

    /**
     * `wm size` reports the display's natural orientation; screenshots and input coordinates use the
     * current orientation. Rotation 1/3 therefore swaps the axes. Rotation 1/3 is computed, and the
     * first emulator verification is at rotation 0 (see the phone-adb provenance notes).
     */
    fun geometry(size: Pair<Int, Int>, rotation: Int): PhoneGeometry? {
        if (rotation !in 0..3) return null
        val (width, height) = if (rotation % 2 == 0) {
            size.first to size.second
        } else {
            size.second to size.first
        }
        if (width !in 1..PhoneLimits.MAX_DISPLAY_PX ||
            height !in 1..PhoneLimits.MAX_DISPLAY_PX
        ) {
            return null
        }
        return PhoneGeometry(displayWidth = width, displayHeight = height, rotation = rotation)
    }
}
