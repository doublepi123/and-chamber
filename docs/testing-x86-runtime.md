# Testing the x86_64 on-device runtime

This is the reproduction recipe for the x86_64 PRoot gate. It needs no hidden binaries: every probe input is
either a repository file or produced by `scripts/provision_x86_probe.py`.

Nothing generated here is committed. Rootfs tarballs, ELF binaries and logs stay under `build/` or a
local scratch directory.

## What is tested, and why each probe exists

The on-device runtime runs PRoot (a ptrace-based user-space `chroot`) around an Alpine (musl) guest. On
x86_64 Android's app seccomp policy rejects the raw `SYS_fork` (x86_64 number 57). PRoot 5.1.107.98 rewrites
that `fork` to `clone(SIGCHLD)` and restores the child's registers. The probes separate the cases:

| Gate | What it proves | Why it is not the same as the others |
|---|---|---|
| `guestShellForksExternalChild` | `/bin/sh -c '/bin/true; echo FORK_OK'` runs an external command in the guest. This is the step the server needs. | busybox calls raw `fork` (57). This is the case that failed on 5.1.107.97. |
| `guestRawForkRegisters` | Raw `syscall 57` reaches the guest, and parent and child both keep the registers rdi, rsi, rdx, r10, r8, r9. | Upstream `tests/fork_regs.c` (raw mode). glibc never issues syscall 57, so this is the only gate that tests the raw fork. |
| `guestSyscallProbeForkFamily` | glibc `fork()` and `clone(SIGCHLD)` in the guest; both return and the child exits 7 and 9. | glibc `fork()` is implemented as `clone`, so this does **not** prove raw `SYS_fork` works. |
| `guestVforkProbeDirect` | Inline-asm `vfork` in the guest; the child exits 42. | A C-level vfork child shares the parent's stack and must not be used. |
| `guestTrueBounded`, `guestShellPrintf` | PRoot exec and shell basics. | Healthy baseline. |

The register check in `fork_regs` writes the registers it sets to known values before the syscall
(`0x1111`, `0x2222`, `0x3333`, `0x4444`, `0x5555`, `0x6666`), and reads them back after it in both processes. A
child that exits 42 has seen its registers preserved; the parent checks the child's exit status.

Diagnostics (`guestDiagnosticListRoot`, `guestDiagnosticShellForkTrace`, `guestDiagnosticProotTraceExecProbe`,
`guestDiagnosticAppNamespacePaths`) record PRoot's `-v 9` output and the app process's path view. They never
count as gate coverage.

## Environment

- Host: Linux x86_64, Android SDK with an emulator that runs an **x86_64 Android 15** image.
- Use a dedicated emulator or serial. Do not use a physical phone or another emulator for this lane.
- The test application id is independent of the production one:
  `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pandchamber.testApplicationId=io.andchamber.x86probe`.
  The production id `io.andchamber.app` is refused by the build.
- `run-as` works only on the debuggable probe build. It is **not** the same as the app's zygote sandbox
  (a process started with `run-as` has a different SELinux context and is not the app's own process). The
  instrumentation runs inside the app process, which is the reference for the gate.

## Steps

1. Provision the native sources (network step, pinned and SHA-checked):

   ```sh
   python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
   ```

2. Provision the probe inputs. `scripts/provision_x86_probe.py` downloads Alpine and SHA-256-checks it against `runtime-manifest.json`; the test itself does not verify these inputs:

   ```sh
   python3 scripts/provision_x86_probe.py --output-dir build/x86-probe
   ```

3. Build the probe application and its instrumentation:

   ```sh
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
       -Pandchamber.testApplicationId=io.andchamber.x86probe --offline
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```

4. Push the probe inputs into the probe app's private cache:

   ```sh
   ADB=adb scripts/push_x86_probe.sh <serial> build/x86-probe io.andchamber.x86probe
   ```

5. Run the probe class:

   ```sh
   adb -s <serial> shell am instrument -w -e class io.andchamber.app.runtime.X86RuntimeProbeTest \
       io.andchamber.x86probe.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Expected: `OK (N tests)` with no failures, where N is the number of `@Test` methods in
   `X86RuntimeProbeTest` (`grep -c '@Test' app/src/androidTest/java/io/andchamber/app/runtime/X86RuntimeProbeTest.kt`).
   Of those, the gate tests in the list above must pass; the `guestDiagnostic*` tests only record output
   and never count as gate coverage.

## Reproducing the end-to-end gates (narrow commands)

These commands reuse the probe application only. They never touch the production package. Replace the
placeholders (`<serial>`) and keep cookie jars and password files outside the repository.

1. Start the server through the app's Home **Start** (or the notification), then forward the port:

   ```sh
   adb -s <serial> forward tcp:23000 tcp:3000
   ```

2. Log in with the UI password and keep the cookie jar outside the repo. The password is read from the
   app's private `ui-password` file via `run-as`, and only its length is printed:

   ```sh
   PW=$(adb -s <serial> shell "run-as io.andchamber.x86probe cat files/runtime/ui-password" | tr -d '\r\n')
   curl -s -c "$JAR" -H 'Content-Type: application/json' \
        -d "$(python3 -c 'import json,sys;print(json.dumps({"password":sys.argv[1]}))' "$PW")" \
        http://127.0.0.1:23000/auth/session
   ```

3. Check health, the actual versions and the metadata:

   ```sh
   curl -s http://127.0.0.1:23000/health
   curl -s -b "$JAR" http://127.0.0.1:23000/api/system/info
   curl -s -b "$JAR" http://127.0.0.1:23000/api/opencode/version
   curl -s -b "$JAR" http://127.0.0.1:23000/api/info
   adb -s <serial> shell "run-as io.andchamber.x86probe cat files/runtime/environment/metadata.json"
   ```

4. Real terminal I/O. The client accepts a marker only as a standalone output line, and the typed command
   builds each marker by shell concatenation so the echo never matches:

   ```sh
   node scripts/x86_probe/pty-io.mjs X86_PTY_OK <out-dir> "$JAR" 23000
   ```

   Expect `REPORT` with `standaloneMarkerLine true`, `gitVersion`, `nodeVersion` and `opencodeVersion` set,
   and `ok: true`. The transcript and report are written to `<out-dir>`.

5. Update: from the Home card with the server running, tap **Update** and confirm. Watch health go down,
   then `metadata.json` change and health return. Check the same APIs as in step 3.

## Upgrade rule

The PRoot pin lives in `runtime_tools/runtime_sources.lock.json` and is mirrored in
`third_party/native-runtime/provenance.json`. Change both together, prepare the new archive, and rerun the
probe. Do not edit the downloaded or generated sources to make a probe pass.

## Established evidence (end-to-end, emulator)

Recorded on an x86_64 emulator running Android 15 (kernel 6.6), with the probe application
`io.andchamber.x86probe` built from the **current branch, not released**. The APK is uncommitted and is
not the v0.0.10 release artefact.

| Area | Result |
|---|---|
| PRoot | 5.1.107.98 (commit `e2d843ee`), built for x86_64 from the verified archive |
| Probe gates | `X86RuntimeProbeTest`: all gate tests green (the diagnostic tests are excluded). Raw `SYS_fork` (57) register check and busybox `fork` pass |
| Install from Home "Set up" | Completes in about 3 minutes. `metadata.json`: abi x86_64, OpenChamber 2.4.0, OpenCode 2.0.32, Node 24.18.1. `node-pty` PTY smoke `PTYOK` |
| Health and API | `/health` 200 (`status ok`); `/api/system/info`, `/api/opencode/version` and `/api/info` agree with metadata |
| Terminal | Real PTY over `/api/terminal/ws`. Standalone output lines from git, node and opencode; marker lines are not echo |
| Stop and start | Stop (notification) leaves no owned process; Start (Home) reaches health 200 within about 20 seconds. PTY works after restart |
| Upgrade policy | `/etc/openchamber/policy.json` is `{"opencodeBinary":"/usr/local/bin/opencode"}`. `upgrade-status` reports `supported: false, reason: policy`. `POST /api/opencode/upgrade` returns 409 and changes nothing |
| Home update | From OpenChamber 2.4.0 / OpenCode 2.0.32 to 2.5.0 / 2.1.1 (registry latest stable at the time). About 3.5 minutes. Health goes down during the update and comes back with the new versions. PTY works after the update and after a restart |

Scope limits for this evidence:

- Emulator only. No physical x86_64 device was used.
- The arm64-v8a build is verified as a build only. No arm64 hardware regression test was run for this branch.
- Several diagnostic runs (`run-as` and `adb shell` views) disagree with the app process. Use the instrumented
  or app-started results above as the reference.
- The first install attempt once hung inside `npx node-gyp` (an empty `proc-log` extraction). A second attempt
  with the same inputs completed. The cause is not established.
- The full update path (Home update to a newer npm release) is verified only from the current branch build.

## Known limits

- The gate runs under the emulator's SELinux policy and kernel (`6.6`, Android 15 x86_64). A physical x86_64
  device, or another kernel, is not covered.
- The probe reads its output from logcat and the instrumentation report. It does not inspect the seccomp
  filter itself; the SIGSYS evidence comes from PRoot's `-v 9` trace.
- The update and install gates run only on the emulator; a physical x86_64 device is not covered.
- Only the probe application has been used for end-to-end runs. The production package is not changed by
  this lane.
