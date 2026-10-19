# ZipZapZop for Android

A WebView wrapper around the same game as the web version (`../web/page.html` + `../web/engine.js`), with fonts bundled so it plays offline.

## Rebuild
```
sudo apt-get install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner
sh build-apk.sh        # -> build/zipzapzop-debug.apk
```
No Gradle or Google SDK download needed. `build-apk.sh` regenerates `assets/index.html` from the web source every time, so game changes only need to be made once.

- Package `com.zipzapzop.zap`, min Android 5.0 (API 21), target API 34.
- Signed with `debug.keystore` (password `android`), which the script creates on first build and git ignores. It is a debug key for sideloading, not for Play Store release.
- Launcher icons come from `make_icons.py`.
