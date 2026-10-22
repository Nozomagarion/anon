#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

team="${1:-${ANON_DEVELOPMENT_TEAM:-}}"
if [ -z "$team" ]; then
  echo "Usage : bash scripts/archive-ios.sh TEAM_ID" >&2
  echo "Le compte Apple correspondant doit déjà être connecté dans Xcode." >&2
  exit 1
fi
if ! [[ "$team" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "Le Team ID doit contenir 10 lettres ou chiffres." >&2
  exit 1
fi
mkdir -p build
xcodebuild -project ANON.xcodeproj -scheme ANON -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/ANON.xcarchive \
  -allowProvisioningUpdates DEVELOPMENT_TEAM="$team" archive

python3 - "$team" <<'PY'
import plistlib, sys
with open('build/ExportOptions.plist', 'wb') as output:
    plistlib.dump({'method': 'debugging', 'teamID': sys.argv[1],
                  'signingStyle': 'automatic', 'manageAppVersionAndBuildNumber': False}, output)
PY
xcodebuild -exportArchive -archivePath build/ANON.xcarchive \
  -exportOptionsPlist build/ExportOptions.plist -exportPath build/Export \
  -allowProvisioningUpdates
