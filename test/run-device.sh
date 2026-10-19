#!/bin/zsh
# Full check on one emulator: 1.5.0 build -> enable biometrics -> in-place upgrade to fork -> decrypt old token -> full fork API run.
# Usage: run-device.sh <serial> [apk-dir]   (APKs: tst-old150.apk and tst-fork.apk, both debug-signed with the same key)
S=$1
D=${2:-"$(dirname $0)/../../dc-fingerprint-auth-test-apks"}
P=dk.danishcrown.testdcejerdk
T=$(dirname $0)
start() { adb -s $S shell am force-stop $P; adb -s $S shell am start -n $P/.MainActivity >/dev/null; sleep ${WAIT:-15}; }

echo "### $S Android $(adb -s $S shell getprop ro.build.version.release) (API $(adb -s $S shell getprop ro.build.version.sdk))"
adb -s $S uninstall $P >/dev/null 2>&1
adb -s $S install "$D/tst-old150.apk" | tail -1
start
echo "== 1.5.0"
node $T/fpcheck.mjs $S avail
node $T/fpcheck.mjs $S encrypt
node $T/fpcheck.mjs $S decrypt
echo "== upgrade to fork (same data)"
adb -s $S install -r "$D/tst-fork.apk" | tail -1
start
node $T/fpcheck.mjs $S avail
node $T/fpcheck.mjs $S decrypt
echo "== fork full run"
node $T/fpcheck.mjs $S full
