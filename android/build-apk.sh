#!/bin/sh
# Builds a signed, ad-free debug APK of ZipZapZop without Gradle, using the Ubuntu-packaged SDK.
# The real build (ads, purchases, release AAB) is Gradle: see README.md.
# Needs:
#   sudo apt-get install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner
# Output: android/build/zipzapzop-debug.apk
set -e
cd "$(dirname "$0")"
SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/obj

# 1. Game page: same source as the web version, with fonts bundled for offline play.
python3 make_page.py

# 2. Manifest: add what Gradle normally fills in (package, SDK levels, version, a placeholder AdMob ID).
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.onurbaykal.zipzapzop" android:versionCode="1" android:versionName="1.0">\n    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />|' \
  -e 's|\${admobAppId}|unused-in-local-build|' AndroidManifest.xml > build/AndroidManifest.xml

# 3. Resources -> R.java, then compile and dex. src-noads stands in for the ads and billing code,
#    whose libraries only Gradle can download, so this build has no ads and no purchase.
aapt package -f -m -J build/gen -M build/AndroidManifest.xml -S res -I "$SDK_JAR"
javac -nowarn -Xlint:-options --release 8 -classpath "$SDK_JAR" -d build/obj \
  $(find src src-noads build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/obj

# 4. Package, align, sign.
aapt package -f -M build/AndroidManifest.xml -S res -A assets -I "$SDK_JAR" -F build/unsigned.apk
(cd build && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/zipzapzop-debug.apk build/aligned.apk
apksigner verify build/zipzapzop-debug.apk
echo "Built $(pwd)/build/zipzapzop-debug.apk"
