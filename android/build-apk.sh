#!/bin/sh
# Builds a signed debug APK of ZipZapZop without Gradle, using the Ubuntu-packaged SDK:
#   sudo apt-get install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner
# Output: android/build/zipzapzop-debug.apk
set -e
cd "$(dirname "$0")"
SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/obj

# 1. Game page: same source as the web version, with fonts bundled for offline play.
python3 - <<'PY'
import re
page = open('../web/page.html').read().replace('/*ENGINE*/', open('../web/engine.js').read())
page = re.sub(r'<link rel="(preconnect|stylesheet)"[^>]*>\n', '', page)
fonts = """<style>
@font-face { font-family: "Bricolage Grotesque"; src: url(fonts/bricolage.woff2) format("woff2"); font-weight: 200 800; }
@font-face { font-family: "Figtree"; src: url(fonts/figtree.woff2) format("woff2"); font-weight: 300 900; }
:root { padding-top: env(safe-area-inset-top, 0px); padding-bottom: env(safe-area-inset-bottom, 0px); }
body { margin: 0; -webkit-tap-highlight-color: transparent; }
[hidden] { display: none !important; }
</style>
"""
head = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">\n'
title_end = page.index('</title>') + len('</title>')
out = head + page[:title_end] + '\n' + fonts + '</head>\n<body>\n' + page[title_end:] + '\n</body>\n</html>\n'
open('assets/index.html', 'w').write(out)
PY

# 2. Resources -> R.java, then compile and dex.
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK_JAR"
javac -nowarn -Xlint:-options --release 8 -classpath "$SDK_JAR" -d build/obj \
  $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/obj

# 3. Package, align, sign.
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$SDK_JAR" -F build/unsigned.apk
(cd build && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/zipzapzop-debug.apk build/aligned.apk
apksigner verify build/zipzapzop-debug.apk
echo "Built $(pwd)/build/zipzapzop-debug.apk"
