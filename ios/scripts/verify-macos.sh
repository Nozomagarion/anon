#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v xcodebuild >/dev/null; then
  echo "Xcode et un runtime iOS Simulator sont nécessaires sur ce Mac." >&2
  exit 1
fi

swift test --package-path ANONCore
xcodebuild -project ANON.xcodeproj -scheme ANON -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO build

destination="${ANON_SIMULATOR_ID:-}"
if [ -z "$destination" ]; then
  destination=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = [d for runtime, items in json.load(sys.stdin)["devices"].items()
           if "iOS" in runtime for d in items if "iPhone" in d["name"] and d.get("isAvailable", True)]
devices.sort(key=lambda d: d.get("state") != "Booted")
if not devices:
    sys.exit("Installer un simulateur iPhone dans Xcode > Settings > Components.")
print(devices[0]["udid"])
')
fi
xcodebuild -project ANON.xcodeproj -scheme ANON -configuration Debug \
  -destination "platform=iOS Simulator,id=$destination" -derivedDataPath build/DerivedData \
  -resultBundlePath "build/Tests-$(date +%s).xcresult" CODE_SIGNING_ALLOWED=NO test
