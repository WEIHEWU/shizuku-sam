#!/bin/zsh
set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
OUT_DIR="${1:-audit-output/reg002-evidence/manual}"
mkdir -p "$OUT_DIR"

snapshot() {
  local label="$1"
  {
    echo "LABEL=$label"
    date -u '+UTC=%Y-%m-%dT%H:%M:%SZ'
    "$ADB" shell settings get global low_power | sed 's/^/global.low_power=/'
    "$ADB" shell settings get global low_power_sticky | sed 's/^/global.low_power_sticky=/'
    "$ADB" shell settings get global battery_saver_enabled | sed 's/^/global.battery_saver_enabled=/'
    "$ADB" shell settings get secure refresh_rate_mode | sed 's/^/secure.refresh_rate_mode=/'
    "$ADB" shell settings get system min_refresh_rate | sed 's/^/system.min_refresh_rate=/'
    "$ADB" shell settings get system peak_refresh_rate | sed 's/^/system.peak_refresh_rate=/'
    "$ADB" shell settings get global device_idle_constants | sed 's/^/global.device_idle_constants=/'
    "$ADB" shell dumpsys power | grep -E '^[[:space:]]*Battery Saver is currently:|mLowPowerModeEnabled|mBatterySaverEnabled|full_enabled' || true
    "$ADB" shell am instrument -w -r -e mode power_truth \
      moe.shizuku.privileged.api.test/moe.shizuku.manager.maxhz.ShellArgMaxInstrumentation
    "$ADB" shell dumpsys power
  } > "$OUT_DIR/$label.txt"
}

run_instrumentation() {
  local label="$1"
  local mode="$2"
  "$ADB" shell am instrument -w -r -e mode "$mode" \
    moe.shizuku.privileged.api.test/moe.shizuku.manager.maxhz.ShellArgMaxInstrumentation \
    > "$OUT_DIR/$label.txt" 2>&1
}

round="${ROUND:-1}"
"$ADB" logcat -c
run_instrumentation "round${round}-restore-result" total_restore
snapshot "round${round}-before"
run_instrumentation "round${round}-daily-result" daily
snapshot "round${round}-t0"
sleep 1
snapshot "round${round}-t1s"
sleep 2
snapshot "round${round}-t3s"
sleep 7
snapshot "round${round}-t10s"
sleep 20
snapshot "round${round}-t30s"
"$ADB" logcat -d -v threadtime > "$OUT_DIR/round${round}-logcat.txt"

if ! grep -q 'PowerManager.isPowerSaveMode=true' "$OUT_DIR/round${round}-t30s.txt" ||
  ! grep -q 'dumpsys_effective=ON' "$OUT_DIR/round${round}-t30s.txt" ||
  ! grep -q '^global.low_power=1$' "$OUT_DIR/round${round}-t30s.txt"; then
  echo "ROUND=${round} RESULT=FAIL REASON=power_saver_not_effective_at_30s" >&2
  exit 4
fi
echo "ROUND=${round} RESULT=PASS"
