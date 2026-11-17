#!/usr/bin/env bash
# Builds Streaks end to end, with no Gradle and no third-party dependencies:
#   1. runs the StreakLogic unit tests (plain JVM, no test framework)
#   2. builds and signs build/streaks.apk (direct install)
#   3. builds build/streaks.aab (Play Store upload) and a universal APK
#      from it (build/streaks-universal.apk), then verifies all artifacts
#
# One-time setup:
#   - JDK 17 (javac, keytool, jarsigner)
#   - Android SDK platform android-35 and build-tools 35.0.0
#     (set ANDROID_SDK_ROOT, or keep the default below)
#   - bundletool jar (BUNDLETOOL env var, or the default below)
#   - signing/streaks.keystore + signing/keystore-password.txt
#     (the password file holds the password twice, on two lines: apksigner
#      reads one line for the keystore and one for the key)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-tools/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
BUNDLETOOL="${BUNDLETOOL:-$HOME/android-tools/bundletool.jar}"
cd "$(dirname "$0")"

mkdir -p build/classes build/dex build/test-classes

echo "== 1/4 unit tests =="
javac -encoding UTF-8 -d build/test-classes \
  src/com/naveenkusakula/streaks/StreakLogic.java test/StreakLogicTest.java
java -cp build/test-classes com.naveenkusakula.streaks.StreakLogicTest

echo "== 1b flow tests (user journeys vs the real storage code) =="
# Test-only: the standalone org.json jar (same library Android ships).
# The app itself has zero dependencies; this jar never enters the APK.
JSONJAR="${JSONJAR:-$HOME/android-tools/test-libs/json.jar}"
if [ -f "$JSONJAR" ]; then
  javac -encoding UTF-8 -cp "$JSONJAR" -d build/test-classes \
    src/com/naveenkusakula/streaks/StreakLogic.java \
    src/com/naveenkusakula/streaks/HabitStore.java test/FlowTest.java
  java -cp "build/test-classes:$JSONJAR" com.naveenkusakula.streaks.FlowTest
else
  echo "SKIPPED: org.json test jar not found at $JSONJAR"
  echo "  fetch: https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar"
fi

echo "== 2/4 APK =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
javac -encoding UTF-8 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q -j unsigned.apk dex/classes.dex)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign \
  --ks signing/streaks.keystore --ks-key-alias streaks \
  --ks-pass file:signing/keystore-password.txt \
  --key-pass file:signing/keystore-password.txt \
  --out build/streaks.apk build/aligned.apk
echo "built build/streaks.apk"

echo "== 3/4 AAB (Play Store) =="
"$BT/aapt2" link --proto-format -o build/proto.zip -I "$JAR" \
  --manifest AndroidManifest.xml build/res.zip
rm -rf build/module && mkdir -p build/module/manifest build/module/dex
(cd build/module && unzip -q ../proto.zip && mv AndroidManifest.xml manifest/ \
  && cp ../dex/classes.dex dex/)
(cd build/module && zip -q -r ../base-module.zip manifest dex res resources.pb)
rm -f build/streaks.aab build/streaks.apks
java -jar "$BUNDLETOOL" build-bundle \
  --modules=build/base-module.zip --output=build/streaks.aab
jarsigner -keystore signing/streaks.keystore \
  -storepass "$(head -1 signing/keystore-password.txt)" \
  -keypass "$(head -1 signing/keystore-password.txt)" \
  build/streaks.aab streaks
java -jar "$BUNDLETOOL" build-apks --bundle=build/streaks.aab \
  --output=build/streaks.apks --mode=universal \
  --ks=signing/streaks.keystore \
  --ks-pass=file:signing/keystore-password.txt \
  --ks-key-alias=streaks --key-pass=file:signing/keystore-password.txt
(cd build && unzip -q -o streaks.apks universal.apk && mv universal.apk streaks-universal.apk)
echo "built build/streaks.aab and build/streaks-universal.apk"

echo "== 4/4 verification =="
for apk in build/streaks.apk build/streaks-universal.apk; do
  echo "-- $apk"
  "$BT/apksigner" verify --verbose "$apk" | grep -E "Verifies|v2 scheme|v3 scheme"
  echo "permissions:"; "$BT/aapt2" dump permissions "$apk"
  "$BT/aapt2" dump badging "$apk" | head -1
done
jarsigner -verify build/streaks.aab >/dev/null && echo "AAB signature verifies"
ls -la build/streaks.apk build/streaks.aab build/streaks-universal.apk
