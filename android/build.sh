#!/usr/bin/env bash
# Builds Streaks (build/streaks.apk) with no Gradle and no third-party
# dependencies: JDK + Android SDK build-tools only.
#
# One-time setup:
#   - JDK 17 (javac, keytool)
#   - Android SDK platform android-35 and build-tools 35.0.0
#     (set ANDROID_SDK_ROOT, or keep the default below)
#   - signing/streaks.keystore + signing/keystore-password.txt
#     (the password file holds the password twice, on two lines: apksigner
#      reads one line for the keystore and one for the key)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-tools/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
cd "$(dirname "$0")"

mkdir -p build/classes build/dex

# Resources + manifest
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip

# Java: Android framework only. -encoding matters (source has UTF-8 glyphs).
javac -encoding UTF-8 -cp "$JAR" -d build/classes $(find src -name '*.java')

# Dex (d8 wants the .class files listed, not the directory)
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')

# Package, align, sign
cp build/base.apk build/unsigned.apk
(cd build && zip -q -j unsigned.apk dex/classes.dex)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign \
  --ks signing/streaks.keystore --ks-key-alias streaks \
  --ks-pass file:signing/keystore-password.txt \
  --key-pass file:signing/keystore-password.txt \
  --out build/streaks.apk build/aligned.apk

echo "Built build/streaks.apk"
"$BT/apksigner" verify --verbose build/streaks.apk | head -3
"$BT/aapt2" dump permissions build/streaks.apk   # expect: package line only, no permissions
