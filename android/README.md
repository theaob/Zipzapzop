# ZipZapZop for Android

A WebView wrapper around the same game as the web version (`../web/page.html` + `../web/engine.js`), with fonts bundled so it plays offline. `make_page.py` writes `assets/index.html` from the web source on every build, so game changes only need to be made once.

## Gradle build (ads, purchases, Play release)
GitHub Actions (`.github/workflows/android.yml`) runs `./gradlew assembleDebug bundleRelease` on every change and uploads the debug APK and the release AAB as artifacts. Locally, with the Android SDK installed: `./gradlew assembleDebug`.

- Package `com.onurbaykal.zipzapzop`, min Android 7.0 (API 24), target and compile API 36.
- Ads: AdMob interstitials between solved Daily/Ladder puzzles (at most one every 3 solves and 3 minutes, never in Learn or before the first daily is solved), after Google's consent form (UMP). Code in `src-ads/.../Monetization.java`.
- Remove ads: one-time Play product `remove_ads`, re-checked on every launch so it survives reinstalls. "Restore purchase" and "Privacy choices" are in the settings sheet.
- Monitoring: Firebase Analytics (events `puzzle_start`, `puzzle_solve`, `tutorial_complete`, plus Firebase's automatic ones) and Crashlytics (crashes and page JavaScript errors), in `src-ads/.../Telemetry.java`. Both stay off until the consent flow allows them. They are built in only when `google-services.json` is present; CI writes it from the `GOOGLE_SERVICES_JSON` secret.
- Debug builds always use Google's test ad IDs. Release builds use `ADMOB_APP_ID` / `ADMOB_INTERSTITIAL_ID` secrets (or `-PadmobAppId=` / `-PadmobInterstitialId=`), falling back to the test IDs.
- Release signing: set the `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` repository secrets; without them the AAB is unsigned.

## Quick local build without Gradle (no ads)
```
sudo apt-get install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner
sh build-apk.sh        # -> build/zipzapzop-debug.apk
```
Uses `src-noads/` in place of the ads, billing and Firebase code, so the APK has no ads, purchase or analytics. Signed with `debug.keystore` (password `android`), created on first build and ignored by git. Launcher icons come from `make_icons.py`.
