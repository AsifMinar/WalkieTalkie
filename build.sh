#!/usr/bin/env bash
# Builds WalkieTalkie.apk without Gradle:
#   aapt (resources) -> javac (Java 8) -> ProGuard (shrink, optimize, obfuscate) -> dx (dex)
#   -> zipalign -> apksigner (APK Signature Scheme v2 + v3)
#
# Just run ./build.sh. Android only installs signed apps, so the build signs the APK
# automatically with signing-key.jks, using the password in signing.env, both from this folder.
# Neither file is in git: back both up somewhere safe.
# (Keep using the same signing-key.jks so new builds install as updates over old ones.)
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR="${SDK_JAR:-/opt/sdk/android-34.jar}"
JAVAC="${JAVAC:-/usr/lib/jvm/java-17-openjdk-amd64/bin/javac}"
PROGUARD="${PROGUARD:-/opt/proguard-7.4.2/lib/proguard.jar}"
export WT_KEYSTORE="${WT_KEYSTORE:-signing-key.jks}"
[ -z "${WT_KEYSTORE_PASS:-}" ] && [ -f signing.env ] && . ./signing.env
export WT_KEYSTORE_PASS="${WT_KEYSTORE_PASS:?set WT_KEYSTORE_PASS or create signing.env}"

rm -rf build && mkdir -p build/gen build/classes build/dex

aapt package -f -m -0 arsc -J build/gen -M AndroidManifest.xml -S res -I "$SDK_JAR" -F build/unaligned.apk

"$JAVAC" -source 8 -target 8 -bootclasspath "$SDK_JAR" -Xlint:-options -nowarn \
  -d build/classes $(find src build/gen -name '*.java')

java -jar "$PROGUARD" -injars build/classes -outjars build/min.jar -libraryjars "$SDK_JAR" \
  -include proguard.pro -printmapping build/mapping.txt >/dev/null

dalvik-exchange --dex --min-sdk-version=26 --output=build/dex/classes.dex build/min.jar
(cd build/dex && aapt add ../unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/unaligned.apk build/aligned.apk

apksigner sign --ks "$WT_KEYSTORE" --ks-key-alias walkie --ks-pass env:WT_KEYSTORE_PASS \
  --min-sdk-version 31 --v2-signing-enabled true --v3-signing-enabled true \
  --out WalkieTalkie.apk build/aligned.apk
rm -f WalkieTalkie.apk.idsig
apksigner verify --min-sdk-version 31 WalkieTalkie.apk
echo "Built $(pwd)/WalkieTalkie.apk ($(du -h WalkieTalkie.apk | cut -f1))"
