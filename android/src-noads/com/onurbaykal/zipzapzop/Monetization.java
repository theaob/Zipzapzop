package com.onurbaykal.zipzapzop;

import android.app.Activity;

// Stand-in used by build-apk.sh, which can't fetch the AdMob and Play Billing libraries.
// The page hides everything ad-related when adsState() reports available: false.
// The Gradle build uses src-ads/ instead.
final class Monetization {
    Monetization(Activity activity, Runnable onChange, HintAdListener onHintAd) {}

    void start() {}
    void puzzleFinished(String mode) {}
    void watchHintAd() {}
    void buyRemoveAds() {}
    void restore() {}
    void showPrivacyOptions() {}
    void destroy() {}

    String stateJson() { return "{\"available\":false}"; }
}
