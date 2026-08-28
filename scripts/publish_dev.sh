#!/usr/bin/env bash
# Publish a dev pre-release APK to GitHub Releases.
#
# IMPORTANT: the GitHub PAT is a SECRET. It is NEVER hardcoded here or in any
# other file. You must supply it as an environment variable at runtime:
#
#     GITHUB_PAT=github_pat_xxx ./scripts/publish_dev.sh
#
# If GITHUB_PAT is unset the script aborts. (Repo is public -> a leaked token
# is auto-revoked.) You can override the repo owner with OWNER=... if needed.
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

GITHUB_PAT="${GITHUB_PAT:-}"
OWNER="${OWNER:-mcap0}"
REPO="${REPO:-whitelister}"

if [ -z "$GITHUB_PAT" ]; then
  echo "ERROR: GITHUB_PAT is not set. Export it before running (do NOT hardcode it in any file):" >&2
  echo '  export GITHUB_PAT=github_pat_xxx   # supplied by the user each run' >&2
  exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "dev" ]; then
  echo "ERROR: must be on the 'dev' branch (currently on '$BRANCH')." >&2
  exit 1
fi

# Read versionName from app/build.gradle.kts (e.g. "1.1.0-dev16").
VERSION_NAME="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed -E 's/versionName = "([^"]+)"/\1/')"
if [ -z "$VERSION_NAME" ]; then
  echo "ERROR: could not determine versionName from app/build.gradle.kts" >&2
  exit 1
fi
TAG="v$VERSION_NAME"

echo "Building $TAG on branch $BRANCH ..."
./gradlew clean assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: APK not found at $APK" >&2
  exit 1
fi

echo "Creating pre-release $TAG ..."
cat > /tmp/release_body.json <<EOF
{
  "tag_name": "$TAG",
  "target_commitish": "dev",
  "name": "Whitelister $TAG",
  "body": "## EXPERIMENTAL pre-release (dev)\\n\\nBuilt via scripts/publish_dev.sh. See commit history for changes.\\n\\n### Unchanged\\nReels blocking (feature 1) works and is untouched.",
  "draft": false,
  "prerelease": true
}
EOF

RID=$(curl -s -H "Authorization: Bearer $GITHUB_PAT" \
  -H "Content-Type: application/json" \
  -d @/tmp/release_body.json \
  "https://api.github.com/repos/$OWNER/$REPO/releases" | grep -m1 '"id"' | grep -oE '[0-9]+')

if [ -z "$RID" ]; then
  echo "ERROR: failed to create release (release id empty). Check GITHUB_PAT and network." >&2
  exit 1
fi
echo "release id: $RID"

echo "Uploading APK ..."
curl -s -H "Authorization: Bearer $GITHUB_PAT" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" \
  "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RID/assets?name=whitelister-$TAG.apk"

echo
echo "Done. Release: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
