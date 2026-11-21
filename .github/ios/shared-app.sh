#!/bin/bash
# Builds Kite's shared app (android/shared, the same code as on Android) for the iPhone project:
#   Frameworks/KiteShared.xcframework        the app itself
#   Frameworks/res/compose-resources         its fonts, pictures and the map's texts
# Usage: .github/ios/shared-app.sh release   (iPhone and simulator, optimised: for Kite.ipa)
#        .github/ios/shared-app.sh sim       (simulator only, quicker: for screenshots)
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
rm -rf Frameworks && mkdir -p Frameworks
cd android
if [ "${1:-release}" = "sim" ]; then
  ./gradlew --no-daemon -PnoAndroid :shared:linkDebugFrameworkIosSimulatorArm64
  xcodebuild -create-xcframework \
    -framework shared/build/bin/iosSimulatorArm64/debugFramework/KiteShared.framework \
    -output "$ROOT/Frameworks/KiteShared.xcframework"
  PLATFORM=iphonesimulator
else
  ./gradlew --no-daemon -PnoAndroid :shared:assembleKiteSharedReleaseXCFramework
  cp -R shared/build/XCFrameworks/release/KiteShared.xcframework "$ROOT/Frameworks/"
  PLATFORM=iphoneos
fi
PLATFORM_NAME=$PLATFORM ARCHS=arm64 BUILT_PRODUCTS_DIR="$ROOT/Frameworks" UNLOCALIZED_RESOURCES_FOLDER_PATH=res \
  ./gradlew --no-daemon -PnoAndroid :shared:syncComposeResourcesForIos
ls "$ROOT/Frameworks" "$ROOT/Frameworks/res/compose-resources/composeResources"
# The Kotlin compiler's helper keeps running (and holding gigabytes) for hours after the build;
# on the Mac runners that starves the iPhone simulator used for the screenshots. Stop it.
pkill -f KotlinCompileDaemon || true
