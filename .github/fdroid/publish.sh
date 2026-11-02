#!/usr/bin/env bash
# Puts the new Kite.apk into Kite's own F-Droid repo, kept on the "fdroid" branch of this
# repository and served straight from GitHub. Add the repo in F-Droid with:
#   https://raw.githubusercontent.com/MatteoFX1/kite-maps/fdroid/repo
set -euo pipefail

APK="$1"            # the signed Kite.apk
VERSION="$2"        # e.g. 0.7.6
WORK="$RUNNER_TEMP/fdroid"
HERE="$(cd "$(dirname "$0")" && pwd)"

rm -rf "$WORK"
if git ls-remote --exit-code --heads origin fdroid >/dev/null 2>&1; then
  git clone --quiet --branch fdroid --single-branch "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$WORK"
else
  # First time: an empty branch with no history of the app's code.
  mkdir -p "$WORK" && cd "$WORK" && git init --quiet -b fdroid
  git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
fi
cd "$WORK"
mkdir -p repo metadata
cp "$HERE/metadata/app.kite.yml" metadata/

# The same key that signs the app signs the repo's index, so there's one secret to keep.
echo "$KITE_KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/repo.jks"
cat > config.yml <<CFG
repo_url: https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/fdroid/repo
repo_name: Kite Maps
repo_description: Kite Maps for Android, straight from its own builds on GitHub.
archive_older: 3
keystore: $RUNNER_TEMP/repo.jks
repo_keyalias: ${KITE_KEY_ALIAS:-kite}
keystorepass: "$KITE_KEYSTORE_PASSWORD"
keypass: "$KITE_KEYSTORE_PASSWORD"
keydname: CN=Kite Maps
CFG
# fdroid signs the index with apksigner from the Android SDK; name it so nothing is left to guessing.
SIGNER=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -z "$SIGNER" ]; then echo "No apksigner in the Android SDK: can't sign the F-Droid index."; exit 1; fi
echo "apksigner: $SIGNER" >> config.yml
chmod 600 config.yml

CODE=$(echo "$VERSION" | awk -F. '{print $1*10000 + $2*100 + $3}')
cp "$APK" "repo/app.kite_${CODE}.apk"
set +e
fdroid update --create-metadata --pretty --use-date-from-apk 2>&1 | grep -v "keystorepass\|keypass"
STATUS=${PIPESTATUS[0]}
set -e
rm -f config.yml "$RUNNER_TEMP/repo.jks"
# Without the signed index F-Droid can't read the repo ("checking repo" forever), so stop loudly.
if [ "$STATUS" -ne 0 ] || [ ! -f repo/index-v1.jar ] || [ ! -f repo/entry.jar ]; then
  echo "fdroid update failed (exit $STATUS): the repo index wasn't made, so nothing is published."
  exit 1
fi
if [ ! -f "repo/app.kite_${CODE}.apk" ]; then
  echo "fdroid moved the APK out of the repo (bad signature?): not publishing."
  exit 1
fi
cp "$HERE/README.md" README.md 2>/dev/null || true

git config user.name "Kite build"
git config user.email "kite-build@users.noreply.github.com"
git add -A repo metadata README.md 2>/dev/null || git add -A repo metadata
git commit --quiet -m "Kite $VERSION" || { echo "Nothing new for F-Droid."; exit 0; }
git push --quiet origin fdroid
echo "F-Droid repo updated with Kite $VERSION."
