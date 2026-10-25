package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbHierarchyParserTest {
    private val geometry = PhoneGeometry(displayWidth = 1080, displayHeight = 2400, rotation = 0)
    private val focus = AdbFocusedWindow(
        currentFocusToken = "7068ee4",
        currentFocusPackage = "com.example.app",
        currentFocusActivity = "com.example.app.MainActivity",
        focusedAppToken = "312191b",
        focusedAppPackage = "com.example.app",
        focusedAppActivity = ".MainActivity",
        focusedAppTask = "16",
    )

    private val sample = """
        <hierarchy rotation="0">
          <node class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1080,2400]" clickable="false" focused="false">
            <node class="android.widget.EditText" package="com.example.app" text="Name" bounds="[10,100][500,200]" clickable="true" focused="true" password="false" />
            <node class="android.widget.Button" package="com.example.app" content-desc="Go" bounds="[10,300][200,400]" clickable="true" focused="false" />
            <node class="android.widget.EditText" package="com.example.app" text="PIN" bounds="[10,500][200,600]" password="true" />
            <node class="android.view.View" package="com.google.android.inputmethod" bounds="[0,2000][1080,2400]" />
            <node class="android.view.View" package="com.example.app" bounds="[2000,2000][2500,2400]" />
            <node class="android.view.View" package="com.example.app" bounds="garbage" />
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parsesPackageRotationAndNodes() {
        val parsed = AdbHierarchyParser.parse(sample.toByteArray())
        assertEquals(0, parsed.rotation)
        assertEquals("com.example.app", parsed.packageName)
        assertEquals(6, parsed.nodes.size)
    }

    @Test
    fun preambleFromUiautomatorIsIgnored() {
        val text = "UI hierchary dumped to: /dev/tty\n" + sample
        val parsed = AdbHierarchyParser.parse(text.toByteArray())
        assertEquals("com.example.app", parsed.packageName)
    }

    @Test
    fun snapshotFiltersOtherPackagesAndOffScreenBounds() {
        val parsed = AdbHierarchyParser.parse(sample.toByteArray())
        val snapshot = AdbSnapshotBuilder.build(parsed, geometry, focus)
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals(4, snapshot.nodes.size)
        assertEquals(snapshot.nodes.map { it.ref }.toSet(), snapshot.refs.keys)
        assertTrue(snapshot.nodes.all { it.bounds.isWithin(geometry) })
        assertEquals("Go", snapshot.nodes[2].label)
        assertTrue(snapshot.nodes[1].editable)
        assertTrue(snapshot.nodes[1].focused)
        // Editable values and password values are never returned as labels.
        assertEquals("", snapshot.nodes[1].label)
        assertEquals("", snapshot.nodes[3].label)
        assertFalse(snapshot.nodes[3].editable)
    }

    @Test
    fun nodeCapKeepsTheFirstNodesAndConsistentRefs() {
        val parsed = AdbHierarchyParser.parse(sample.toByteArray())
        val snapshot = AdbSnapshotBuilder.build(parsed, geometry, focus, maxNodes = 2)
        assertEquals(2, snapshot.nodes.size)
        assertEquals(setOf("n0", "n1"), snapshot.refs.keys)
    }

    @Test
    fun labelsRolesAndRefsAreBounded() {
        val longLabel = "x".repeat(PhoneLimits.MAX_NODE_LABEL_CHARS + 50)
        val xml = """
            <hierarchy rotation="0">
              <node class="android.widget.TextView" package="com.example.app" text="$longLabel" bounds="[0,0][100,100]" />
            </hierarchy>
        """.trimIndent()
        val parsed = AdbHierarchyParser.parse(xml.toByteArray())
        val snapshot = AdbSnapshotBuilder.build(parsed, geometry, focus)
        assertEquals(PhoneLimits.MAX_NODE_LABEL_CHARS, snapshot.nodes.single().label.length)
        assertTrue(snapshot.nodes.single().role.length <= PhoneLimits.MAX_ROLE_CHARS)
        assertTrue(snapshot.nodes.single().ref.matches(Regex("^[A-Za-z0-9:_-]{1,64}$")))
    }

    @Test
    fun doctypeAndEntityDeclarationsAreRejected() {
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(
                "<!DOCTYPE hierarchy [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>$sample".toByteArray(),
            )
        }
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(
                "<hierarchy rotation=\"0\"><node text=\"&x;\" bounds=\"[0,0][1,1]\" /></hierarchy>"
                    .replace("&x;", "<!ENTITY x \"boom\">")
                    .toByteArray(),
            )
        }
    }

    @Test
    fun oversizedInputIsRejectedBeforeParsing() {
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(ByteArray(AdbHierarchyParser.MAX_XML_BYTES + 1))
        }
    }

    @Test
    fun nulAndMissingHierarchyAreRejected() {
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(sample.replace("com.example.app", "com.example\u0000app").toByteArray())
        }
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse("not xml".toByteArray())
        }
    }

    @Test
    fun malformedXmlAndInvalidRotationAreRejected() {
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse("<hierarchy rotation=\"0\"><node".toByteArray())
        }
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse("<hierarchy rotation=\"9\"></hierarchy>".toByteArray())
        }
    }

    @Test
    fun missingPackageIsRejected() {
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(
                "<hierarchy rotation=\"0\"><node class=\"A\" bounds=\"[0,0][1,1]\" /></hierarchy>"
                    .toByteArray(),
            )
        }
    }

    @Test
    fun snapshotWindowIdIsAStableWindowAndContentWitness() {
        val parsed = AdbHierarchyParser.parse(sample.toByteArray())
        val first = AdbSnapshotBuilder.build(parsed, geometry, focus).windowId
        val second = AdbSnapshotBuilder.build(parsed, geometry, focus).windowId
        assertEquals(first, second)
        assertNotNull(AdbWindowWitness.parse(first))
        assertTrue(first.length <= PhoneLimits.MAX_WINDOW_ID_CHARS)
        assertFalse(first.any { it.isISOControl() })

        val otherWindow = AdbSnapshotBuilder.build(
            parsed,
            geometry,
            focus.copy(currentFocusToken = "1234567"),
        ).windowId
        assertFalse(first == otherWindow)

        val changedContent = sample.replace("content-desc=\"Go\"", "content-desc=\"Leave\"")
        assertFalse(
            first == AdbSnapshotBuilder.build(
                AdbHierarchyParser.parse(changedContent.toByteArray()),
                geometry,
                focus,
            ).windowId,
        )
    }
}
