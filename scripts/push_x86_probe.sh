#!/bin/sh
# Pushes the x86_64 probe inputs (scripts/provision_x86_probe.py output) into the independent probe app.
# Only the probe package is touched; the production app and other devices are never addressed.
#
# Usage: scripts/push_x86_probe.sh <serial> <probe-output-dir> [probe-app-id]
#   serial             adb serial of the x86_64 emulator, e.g. emulator-5554
#   probe-output-dir   build/x86-probe (from provision_x86_probe.py)
#   probe-app-id       default io.andchamber.x86probe (must match the -Pandchamber.testApplicationId build)
set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <serial> <probe-output-dir> [probe-app-id]" >&2
    exit 2
fi

SERIAL=$1
OUT=$2
APP=${3:-io.andchamber.x86probe}
ADB=${ADB:-adb}

# APP is interpolated into `sh -c` on the device, so it must be a plain Java package name. Only the strict
# form below passes; any shell metacharacter (quote, space, $, ;, backtick) is rejected before use.
case "$APP" in
    io.andchamber.app | io.andchamber.app.*)
        echo "refusing to push into the production application id ($APP)" >&2
        exit 2
        ;;
esac
if ! printf '%s' "$APP" | grep -Eq '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$'; then
    echo "refusing: application id '$APP' is not a plain package name (letters, digits, underscores, dots)" >&2
    exit 2
fi

[ -f "$OUT/alpine-x86_64.tar.gz" ] || { echo "missing $OUT/alpine-x86_64.tar.gz; run provision_x86_probe.py" >&2; exit 2; }

REMOTE_TMP=/data/local/tmp/andchamber-x86-probe
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE_TMP && mkdir -p $REMOTE_TMP"
for file in alpine-x86_64.tar.gz fork_regs sysprobe vforkasm; do
    "$ADB" -s "$SERIAL" push "$OUT/$file" "$REMOTE_TMP/$file" >/dev/null
done

# Copy into the probe app's private cache with run-as (debuggable build only). The test extracts the
# rootfs from cache/alpine-x86_64.tar.gz and installs the static probes from cache/x86-probe/.
"$ADB" -s "$SERIAL" shell "run-as $APP sh -c 'mkdir -p cache/x86-probe && cat $REMOTE_TMP/alpine-x86_64.tar.gz > cache/alpine-x86_64.tar.gz && chmod 600 cache/alpine-x86_64.tar.gz'"
for file in fork_regs sysprobe vforkasm; do
    "$ADB" -s "$SERIAL" shell "run-as $APP sh -c 'cat $REMOTE_TMP/$file > cache/x86-probe/$file && chmod 700 cache/x86-probe/$file'"
done
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE_TMP" >/dev/null 2>&1 || true
echo "pushed the Alpine tarball and the three static probes into $APP cache"
echo "next: run the probe (docs/testing-x86-runtime.md, 'Run the probe')"
