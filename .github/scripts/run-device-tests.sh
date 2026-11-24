#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/outputs/androidTest-results

net_name="${NNUE_NET_NAME:-nn-1a298aa575a0.nnue}"
cached_net="/tmp/nnue-cache/${net_name}"
debug_apk=$(find app/build/outputs/apk/debug -maxdepth 2 -name "*.apk" | head -n 1 || true)

if [[ -n "${debug_apk}" && -f "${cached_net}" ]]; then
  echo "Pre-staging ${net_name} into ${debug_apk} app sandbox..."
  if adb install -r -t "${debug_apk}"; then
    if adb push "${cached_net}" "/data/local/tmp/${net_name}"; then
      adb shell run-as com.example.chessmentor sh -c \
        "mkdir -p files/nnue && cp /data/local/tmp/${net_name} files/nnue/${net_name}" || true
      adb shell rm -f "/data/local/tmp/${net_name}" || true
    fi
  fi
fi

set +e
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.build.abi=x86_64 \
  --no-daemon --stacktrace --warning-mode=all \
  -Dorg.gradle.jvmargs="-Xmx3072m -Dfile.encoding=UTF-8" 2>&1 | tee /tmp/device-test.log
status=${PIPESTATUS[0]}
set -e

if [[ "${status}" -ne 0 ]]; then
  adb logcat -d -t 1000 > app/build/outputs/androidTest-results/emulator-logcat.txt || true
  python3 - /tmp/device-test.log <<'PY'
import sys
lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
tail = " | ".join(l.strip() for l in lines[-35:] if l.strip())
if tail:
    print(f"::error title=DeviceTestGradle::{tail[:1200]}")
PY
  exit "${status}"
fi
