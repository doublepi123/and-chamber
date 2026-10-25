# Phone ADB dependencies (LibADB Android, SPAKE2, Bouncy Castle)

This directory holds the license texts and provenance for the native ADB transport used by
`app/src/main/java/io/andchamber/app/phone/adb/`. The license files are copied into the APK under
`assets/licenses/phone-adb/` by `syncPhoneAdbLicenseAssets`.

## Components

| Component | Version | License used here | Source |
|---|---|---|---|
| LibADB Android (`com.github.MuntashirAkon:libadb-android`) | 3.1.1 | Apache-2.0 (upstream is dual GPL-3.0-or-later OR Apache-2.0; this app uses the Apache-2.0 option) | https://github.com/MuntashirAkon/libadb-android/tree/3.1.1 |
| spake2-android (`com.github.MuntashirAkon.spake2-java:spake2-android`) | 2.2.1 | LGPL-3.0 | https://github.com/MuntashirAkon/spake2-java/tree/2.2.1 |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk15to18`) | 1.81 | Bouncy Castle Licence (MIT-style) | https://www.bouncycastle.org/ |
| Conscrypt (`org.conscrypt:conscrypt-android`) | 2.5.3 | Apache-2.0 | https://github.com/google/conscrypt |
| androidx.annotation | 1.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |

LibADB also contains files marked `BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)` (the
`AdbConnection`/`AdbStream`/`AdbProtocol` files derived from Cameron Gutman's AdbLib). The
BSD-3-Clause text is included as well.

License texts in this directory:

- `LICENSE-Apache-2.0.txt`
- `LICENSE-LGPL-3.0.txt` (the LGPL-3.0 text; it incorporates the GPL-3.0 terms)
- `LICENSE-GPL-3.0.txt` (the GPL-3.0 text referenced by the LGPL and by LibADB's other license option)
- `LICENSE-BSD-3-Clause.txt`
- `LICENSE-BouncyCastle.txt`

## Actual LibADB 3.1.1 API used

Verified against the 3.1.1 tag, not a later branch:

- `AbsAdbConnectionManager.java`: `pair(host, port, code)`, `connect(host, port)`,
  `openStream(destination)`; `setTimeout`; `getAdbConnection`.
  https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AbsAdbConnectionManager.java
- `AdbConnection.java`: `Builder.setPrivateKey/setCertificate/setDeviceName/setApi`,
  `connect(timeout, unit, throwOnUnauthorised)`, `open(destination)`, `close()`. A connection
  starts with a plaintext `CNXN`; on `A_STLS` from the daemon it upgrades the same socket to
  TLS 1.3. `mIsTls` is private and has no getter in 3.1.1.
  https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AdbConnection.java
- `PairingConnectionCtx.java`: SPAKE2 client, TLS 1.3 exported keying material appended to the
  pairing code, encrypted peer-info exchange. It is `final`; its socket is a local variable; there
  are no connect/read timeouts; `close()` dereferences null streams before the TLS step, and does
  not interrupt an in-progress handshake.
  https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java
- `PairingAuthCtx.java`: package-private SPAKE2/HKDF/AES-GCM implementation
  (`createAlice`, `getMsg`, `initCipher`, `encrypt`, `decrypt`, `destroy`).
  https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/PairingAuthCtx.java
- `SslUtils.java`: package-private TLS 1.3 context; trusts any peer certificate (ADB pairing
  authenticates by the paired key, not a CA). It caches one `SSLContext` in a static field, which
  pins the first client identity in the process.
  https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/SslUtils.java
- Streams: `shell:uiautomator dump /dev/tty`, `exec:screencap -p`, and fixed
  `input tap|swipe|text|keyevent` destinations opened through `AdbConnection.open`.

## App-local patches

`app/src/main/java/io/github/muntashirakon/adb/AndChamberAdbTransport.kt` is AndChamber code
(SPDX `Apache-2.0`), derived from `PairingConnectionCtx.java` under LibADB's Apache-2.0 option.
It lives in the LibADB package on purpose: it reuses the package-private `PairingAuthCtx`,
`AndroidPubkey`, `AdbProtocol` and `SslUtils` instead of copying SPAKE2 or writing a password
protocol. The exact changes are listed in the file header:

1. The socket is a field with bounded `connect` and `SO_TIMEOUT`.
2. `close()` is idempotent and null-safe at every stage, and shuts the socket down so a blocked
   handshake/read ends; `cancel()` calls it from a watchdog.
3. The whole pairing operation has a monotonic deadline, checked before every read/write.
4. Failures are typed (`WRONG_CODE`, `PROTOCOL`, `DEADLINE`, `UNREACHABLE`, `TLS_*`) and never carry
   the code or a packet.
5. The SPAKE2 message, key derivation, AES-GCM peer-info encryption and 6-byte packet framing are
   unchanged upstream logic. Android 15 adbd sends peer-info type `ADB_DEVICE_GUID` (1), while the
   upstream client only accepted `ADB_RSA_PUB_KEY` (0); both documented types are accepted. The
   payload is GCM-authenticated and is not used as key material.
6. `AndChamberAdbTls` adds an `A_STLS` preflight and reads the pinned `mIsTls` field after connect;
   a plaintext connection is closed and refused. If a future LibADB version renames that field the
   check fails closed (connection refused), not open.
7. `AndChamberAdbTls.prepareContext()` clears LibADB's cached `SSLContext` before every identity
   use, so a second identity cannot authenticate with the first identity's client certificate.
8. `AndChamberAdbStreamReader.kt` replaces the broken `AdbStream.read()` completion path for the
   pinned 3.1.1 layout. In upstream `AdbStream.notifyClose(true)`, a non-empty `mReadQueue` only sets
   `mPendingClose=true` and leaves `mIsClosed=false`; the next `read()` returns the queued data, then
   waits on `mReadQueue` while `!mIsClosed`, ignoring the pending close, and its final pending-close
   check is unreachable. A short response that queues payload then sends CLSE therefore hangs until
   the watchdog closes the link. The app-local reader runs the same single-reader buffer/queue loop
   under the same `mReadQueue` monitor, checks `mPendingClose` in the wait predicate, drains the
   buffer and queue before EOF, and does not call the broken wait path. It reflects the pinned fields
   `mReadQueue`, `mReadBuffer`, `mIsClosed` and `mPendingClose`; a missing/mistyped field fails closed
   as an adapter exception (never a normal EOF). `LibAdbDevice.LibAdbStream` uses this reader instead
   of `AdbStream.read`.
   Source: https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AdbStream.java
   Tests: `app/src/test/java/io/github/muntashirakon/adb/AndChamberAdbStreamReaderTest.kt` constructs a
   real 3.1.1 `AdbStream` through a test-only Unsafe instance (same package, real `addPayload`/
   `notifyClose`) and proves the shipped read hangs after queued payload + CLSE while the adapter
   returns all data then EOF; it also covers segmented payloads, empty CLSE, delayed CLSE and real
   reader + dead-link -> OUTCOME_UNKNOWN. No protocol, cipher or new dependency is involved.

The library AAR itself is not modified. If you change `libadb` or `spake2-android`, rebuild them
from the tagged source and substitute the artifact in Gradle (see below); the app source is MIT and
can be relinked.

## Rebuild / relink (LGPL-3.0 compliance)

The APK contains `spake2-android` 2.2.1 as a compiled AAR. Its complete corresponding source is the
tagged repository above; the app source is this repository. To exercise the LGPL relink right:

```sh
git clone https://github.com/MuntashirAkon/spake2-java.git
cd spake2-java
git checkout 2.2.1
./gradlew :spake2-android:assembleRelease
# The AAR appears under spake2-android/build/outputs/aar/
```

Then replace the dependency with a local file or a Gradle composite build, for example:

```kotlin
// app/build.gradle.kts (local experiment only; not the committed build)
implementation(files("/path/to/spake2-android-release.aar"))
```

and remove the transitive JitPack artifact (or use a dependency substitution) so only the rebuilt
library is packaged. Run `./gradlew :app:assembleDebug` after the substitution. The same procedure
works for a rebuilt `libadb-android` AAR.

No Bouncy Castle source is modified; bcprov 1.81 is used as published. `conscrypt-android` 2.5.3
is added because the platform's `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` is a
hidden API that API 35 denies to reflection (`blocked, reflection, denied` in logcat); LibADB's
README recommends the bundled provider for this case. The transport never falls back to plaintext
when the provider is unavailable: it fails with `TLS_UNAVAILABLE`.

## Environment caveat (secure=0)

On a `ro.adb.secure=0` (userdebug) image, adbd accepts any client key without authorization. A
successful connection is therefore **not** evidence of pairing, and an unpaired fresh identity can
connect at the adbd level. The driver requires its own local pairing receipt from
`AdbPhonePairing.pair()` before connecting, and the P0 probe still verifies the wrong-code and
correct-code exchange itself. The TLS-only check is independent of this caveat.

Rotation 1/3 geometry is computed by swapping the `wm size` axes. The first P0 run is at rotation 0;
rotated capture/observe is not part of the verified set until a device run covers it.
