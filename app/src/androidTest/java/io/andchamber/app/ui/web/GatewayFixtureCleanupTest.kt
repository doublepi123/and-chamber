package io.andchamber.app.ui.web

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import io.andchamber.app.data.profileNameFor
import io.andchamber.app.runtime.WebSessionIdentity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A second instrumentation invocation releases Chromium's process-lifetime profile references. */
@RunWith(AndroidJUnit4::class)
class GatewayFixtureCleanupTest {
    @Test fun deleteOnlyRegisteredFixtureProfiles() {
        // Optional explicit fixture names allow cleanup after a test-process crash before registration.
        val arguments = InstrumentationRegistry.getArguments()
        val recovery = arguments.getString("fixtureProfiles")
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        // Recovery names must come from the exact earlier failed fixture report, never enumeration.
        if (recovery.isNotEmpty()) {
            val oldPid = requireNotNull(arguments.getString("fixtureProcessId")).toInt()
            recovery.forEach { GatewayFixtureProfiles.registerRecovery(it, oldPid) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ProfileStore.getInstance()
            for (name in GatewayFixtureProfiles.pending()) {
                assertTrue("Invalid fixture profile name", name.matches(Regex("andchamber_v1_[0-9a-f]{64}")))
                val metadata = GatewayFixtureProfiles.metadata(name).split('|')
                val oldPid = metadata.last().toInt()
                assertTrue("Cleanup requires a fresh instrumentation PID", oldPid != Process.myPid())
                assertFalse("Fixture process must have exited before cleanup", File("/proc/$oldPid").exists())
                if (metadata.first() != "recovery") {
                    val targetId = metadata[0]
                    UUID.fromString(targetId.removePrefix("webview-test-"))
                    assertTrue("Only fixture UUID targets may be deleted", targetId.startsWith("webview-test-"))
                    assertTrue("Only fixture loopback origins may be deleted", metadata[1].matches(Regex("http://localhost:[0-9]+")))
                    assertTrue("Profile must match registered target and origin", name == profileNameFor(WebSessionIdentity(targetId, metadata[1])))
                }
                Log.i("GatewayFixtureCleanup", "oldPid=$oldPid cleanupPid=${Process.myPid()} profile=$name")
                assertTrue("Fixture profile must be deletable in a fresh process", store.deleteProfile(name) || name !in store.allProfileNames)
                GatewayFixtureProfiles.forget(name)
            }
        }
        assertTrue("All fixture profiles were deleted", GatewayFixtureProfiles.pending().isEmpty())
    }
}

internal object GatewayFixtureProfiles {
    private val preferences get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("webview_regression_fixture_profiles", Context.MODE_PRIVATE)

    fun pending(): Set<String> = preferences.getStringSet("names", emptySet())!!.toSet()
    fun metadata(name: String): String = checkNotNull(preferences.getString(name, null))
    fun register(name: String, identity: WebSessionIdentity) {
        check(preferences.edit().putStringSet("names", pending() + name)
            .putString(name, "${identity.targetId}|${identity.origin}|${Process.myPid()}").commit())
    }
    fun registerRecovery(name: String, oldPid: Int) {
        check(preferences.edit().putStringSet("names", pending() + name)
            .putString(name, "recovery|$oldPid").commit())
    }
    fun forget(name: String) {
        check(preferences.edit().putStringSet("names", pending() - name).remove(name).commit())
    }
}
