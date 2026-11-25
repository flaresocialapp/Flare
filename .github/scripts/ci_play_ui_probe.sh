#!/usr/bin/env bash
set -euo pipefail

test_filter=$(sed -n 's/^ *\.\/gradlew :app:connectedDebugAndroidTest -Pandroid\.testInstrumentationRunnerArguments\.class=//p' .github/workflows/android.yml)
[[ -n "$test_filter" && "$test_filter" != *$'\n'* ]]
device=$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')
test -n "$device"
{
  adb -s "$device" shell getprop ro.build.fingerprint
  adb -s "$device" shell getprop ro.product.cpu.abi
  adb -s "$device" shell df -h /data
  adb -s "$device" shell cat /proc/meminfo | sed -n '1,8p'
} | tee "$FLARE_UI_DIAG/device.txt"

attempts=1
if [[ "$FLARE_UI_MODE" == repeated-ui ]]; then
  attempts=3
fi

for (( attempt=1; attempt<=attempts; attempt++ )); do
  result_dir="$FLARE_UI_DIAG/attempt-$attempt"
  mkdir -p "$result_dir"
  start=$(date '+%m-%d %H:%M:%S.000')
  echo "Running Android UI regression suite, attempt $attempt of $attempts"
  df -hP "$GITHUB_WORKSPACE" "$RUNNER_TEMP" | tee "$result_dir/disk-before.txt"
  status=0
  timeout -k 15s 25m ./gradlew :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_filter" \
    --console=plain --info --stacktrace > "$result_dir/gradle.log" 2>&1 || status=$?
  echo "$status" > "$result_dir/gradle-exit.txt"
  adb -s "$device" logcat -d -T "$start" -b main -b crash -b system -v threadtime \
    TestRunner:V AndroidRuntime:V Instrumentation:V UiAutomation:V System.err:W ActivityManager:I ActivityTaskManager:I '*:S' \
    > "$result_dir/logcat.txt" 2>&1 || true
  df -hP "$GITHUB_WORKSPACE" "$RUNNER_TEMP" | tee "$result_dir/disk-after.txt"
  while IFS= read -r -d '' report; do
    target="$result_dir/results/${report#app/build/}"
    mkdir -p -- "$(dirname "$target")"
    cp -- "$report" "$target"
  done < <(find app/build -type f -path '*connected*' \( -name 'TEST-*.xml' -o -name '*result*.pb' -o -name 'utp*.log' \) -print0)
  python3 - "$result_dir" <<'PY'
from pathlib import Path
import sys
from xml.etree import ElementTree

root = Path(sys.argv[1])
for report in sorted((root / "results").rglob("TEST-*.xml")):
    suite = ElementTree.parse(report).getroot()
    print(report.relative_to(root), {key: suite.attrib[key] for key in ("tests", "failures", "errors", "skipped") if key in suite.attrib})
    for case in suite.iter("testcase"):
        verdict = "passed"
        for tag in ("failure", "error", "skipped"):
            detail = case.find(tag)
            if detail is not None:
                verdict = tag
                break
        print(f"{verdict}: {case.get('classname')}.{case.get('name')}")
        if verdict in ("failure", "error") and detail is not None:
            print(detail.get("message", ""))
            print("\n".join((detail.text or "").splitlines()[:80]))
PY
  if (( status != 0 )); then
    tail -80 "$result_dir/gradle.log"
    rg -n 'failed:|FATAL EXCEPTION|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed' "$result_dir/logcat.txt" | tail -40 || true
    exit "$status"
  fi
  tail -10 "$result_dir/gradle.log"
done
