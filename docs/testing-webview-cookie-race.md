# Real WebView cookie race regression

Run on a dedicated emulator with a WebView supporting `MULTI_PROFILE`:

```sh
ANDROID_SERIAL=emulator-5580 ANDROID_HOME="$HOME/Android/Sdk" ./gradlew \
  :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.andchamber.app.ui.web.GatewayCookieRaceTest

# Required: fresh instrumentation process deletes only profiles registered by fixtures.
ANDROID_SERIAL=emulator-5580 ANDROID_HOME="$HOME/Android/Sdk" ./gradlew \
  :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.andchamber.app.ui.web.GatewayFixtureCleanupTest
```

Do not omit `ANDROID_SERIAL` when other devices are connected. Do not clear or
uninstall the target app. Keep the `leaveApksInstalledAfterRun` flag: it prevents
the connected-test harness from uninstalling APKs during cleanup.
Fixtures use unique UUID target/profile identities and
delete only their own profiles. Chromium retains in-process profile references
even after view destruction, so the second invocation is required for cleanup.
Only fixture profile names are recorded in a separate test-specific preference;
no saved remote entries are read or changed. Missing `MULTI_PROFILE` fails rather than skips.
Cleanup validates the registered UUID target and loopback origin against the
production profile-name derivation, and requires a new PID with the creating
process gone. A cleanup failure is a failed test and leaves its registration
intact for recovery; do not replace it with a broad profile-data clear.
Always use both class-filtered invocations above, including cleanup after a
failed main suite. Unfiltered discovery may run cleanup in the same process
and must not be used as the regression procedure.
The activity host exists only in the debug APK; all test libraries are
`androidTestImplementation` dependencies, not release dependencies.

The MockWebServer dispatcher holds **response headers**, not only a response
body, until native authentication has committed through the actual bound
CookieManager callback. The baseline intentionally omits production handoff and
passes only when the stale 401 deletes the fresh cookie. Controls distinguish
early expiry from late 401 without expiry. The fixed case calls the production
handoff API, reads the cookie capability after its barrier, and requires the
same profile, a retained CF-like marker, and authenticated
`/?session=handoff-test`. Polls wait for observable browser/cookie conditions;
elapsed time alone is never success evidence. Passwords and cookies are dummy
fixtures; no saved remotes or credentials are read.

The two recovery cases require a cancelled completed barrier to retry on the
same underlying WebView with a fresh document (verified with a JS global
sentinel), and a detached pooled view to re-enter with a new callback owner and
ticket without reviving the old owner. They also retain the marker/profile and
finish the authenticated session path. Recovery includes an actual Chromium
connection failure on a new page, requiring its error to become visible and a
subsequent valid load to clear it. Real stale internal-error delivery cannot be
forced reliably through public WebView APIs; callback-guard JVM tests cover that
specific synthetic ordering rather than claiming this harness proves it.
