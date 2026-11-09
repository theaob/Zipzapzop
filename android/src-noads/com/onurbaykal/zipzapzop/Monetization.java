package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.view.ViewGroup;

// Stand-in used by build-apk.sh, which can't fetch the AdMob and Play Billing libraries.
// The page hides everything ad-related when adsState() reports available: false.
// The Gradle build uses src-ads/ instead.
final class Monetization {
    Monetization(Activity activity, Runnable onChange, RewardedAdListener onRewarded) {}

    interface BannerListener { void bannerReady(int heightDp); }

    void start() {}
    void attachBanner(ViewGroup host, BannerListener listener) {}
    void setBannerWanted(boolean wanted) {}
    void sizeBanner(int widthDp, int maxDp) {}
    void watchRewardedAd(String purpose) {}
    void buyRemoveAds() {}
    void buyEndless() {}
    void restore() {}
    void showPrivacyOptions() {}
    void destroy() {}

    String stateJson() { return "{\"available\":false}"; }
}
