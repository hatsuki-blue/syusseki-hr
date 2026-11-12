#!/bin/bash
set -e
cd "$(dirname "$0")"
JAR="${ANDROID_JAR:-$HOME/Android/Sdk/platforms/android-34/android.jar}"
rm -rf build && mkdir -p build/gen build/classes build/dex build/res
aapt2 compile --dir res -o build/res/res.zip
aapt2 link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --java build/gen --min-sdk-version 31 --target-sdk-version 34 --version-code 10 --version-name 1.9 \
  --warn-manifest-validation build/res/res.zip
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -classpath "$JAR" \
  -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "^warning: \[options\]\|^Note:\|bootstrap class path\|^[0-9]* warning" || true
ls build/classes/jp/yagi/attendqr/MainActivity.class >/dev/null
dalvik-exchange --dex --min-sdk-version=26 --output=build/dex/classes.dex build/classes
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks -storepass attendqr -keypass attendqr -alias key \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AttendQR" >/dev/null 2>&1
apksigner sign --ks keystore.jks --ks-pass pass:attendqr --key-pass pass:attendqr --ks-key-alias key \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out build/AttendQR.apk build/aligned.apk
apksigner verify --verbose build/AttendQR.apk | head -6
ls -la build/AttendQR.apk
