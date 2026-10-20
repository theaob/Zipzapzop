package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// AdMob interstitials between solved puzzles, Google's consent flow (UMP), and the one-time
// "remove_ads" Play purchase. Every callback that touches state runs on the main thread.
final class Monetization {
    static final String PRODUCT = "remove_ads";
    // At most one ad every SOLVES_PER_AD solves and MIN_GAP_MS, never before the first daily is done.
    private static final int SOLVES_PER_AD = 3;
    private static final long MIN_GAP_MS = 3 * 60 * 1000;

    private final Activity activity;
    private final Runnable onChange;
    private final SharedPreferences prefs;
    private final ConsentInformation consent;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private BillingClient billing;
    private ProductDetails product;
    private InterstitialAd interstitial;
    private boolean loading;
    private int solvesSinceAd;
    private long lastAdAt = -MIN_GAP_MS;
    private String message;

    Monetization(Activity activity, Runnable onChange) {
        this.activity = activity;
        this.onChange = onChange;
        this.prefs = activity.getSharedPreferences("monetization", Activity.MODE_PRIVATE);
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    private boolean adsRemoved() { return prefs.getBoolean("adsRemoved", false); }

    void start() {
        startBilling();
        // Ask for consent where the law requires it (EEA, UK, Switzerland, some US states), then start ads.
        // The status is also fetched after ads are removed, because analytics and crash reports follow it.
        consent.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().build(),
            () -> {
                if (adsRemoved()) { onChange.run(); return; }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> {
                    startAds();
                    onChange.run(); // the "Privacy choices" button may now be required
                });
            },
            requestError -> startAds()); // offline or misconfigured: fall back to any earlier consent
        startAds(); // consent from an earlier launch is enough to start right away
    }

    private void startAds() {
        if (adsRemoved() || !consent.canRequestAds() || !adsStarted.compareAndSet(false, true)) return;
        MobileAds.initialize(activity, status -> loadInterstitial());
    }

    private void loadInterstitial() {
        if (loading || interstitial != null || adsRemoved() || !consent.canRequestAds()) return;
        loading = true;
        InterstitialAd.load(activity, BuildConfig.ADMOB_INTERSTITIAL, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(InterstitialAd ad) { loading = false; interstitial = ad; }

                @Override
                public void onAdFailedToLoad(LoadAdError error) { loading = false; } // retried after the next solve
            });
    }

    // Called by the page when the player taps Next on a solved Daily or Ladder puzzle.
    void puzzleFinished(String mode) {
        if ("daily".equals(mode)) prefs.edit().putBoolean("firstDailyDone", true).apply();
        if (adsRemoved() || "learn".equals(mode) || !prefs.getBoolean("firstDailyDone", false)) return;
        solvesSinceAd++;
        long now = SystemClock.elapsedRealtime();
        if (interstitial == null) { loadInterstitial(); return; }
        if (solvesSinceAd < SOLVES_PER_AD || now - lastAdAt < MIN_GAP_MS) return;
        InterstitialAd ad = interstitial;
        interstitial = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() { loadInterstitial(); }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) { loadInterstitial(); }
        });
        solvesSinceAd = 0;
        lastAdAt = now;
        ad.show(activity);
    }

    void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> onChange.run());
    }

    // ---- Play Billing: one non-consumable product ----

    private void startBilling() {
        billing = BillingClient.newBuilder(activity)
            .setListener(this::onPurchasesUpdated)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build();
        connect(null, false);
    }

    // Runs `then` once billing is connected, or tells the player why it couldn't connect when `report` is set.
    private void connect(Runnable then, boolean report) {
        if (billing.isReady()) { if (then != null) then.run(); return; }
        billing.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                activity.runOnUiThread(() -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        if (report) say(result.getResponseCode() == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
                            ? "Google Play purchases aren't available on this device or account."
                            : "Couldn't reach Google Play. Check your connection and try again.");
                        return;
                    }
                    queryProduct(null);
                    checkOwned(false); // also restores the purchase on a new phone or after a reinstall
                    if (then != null) then.run();
                });
            }

            @Override
            public void onBillingServiceDisconnected() {} // reconnects on the next buy or restore
        });
    }

    // Loads the product and its local price; `then` runs afterwards whether or not it was found.
    private void queryProduct(Runnable then) {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()))
            .build();
        billing.queryProductDetailsAsync(params, (result, details) -> activity.runOnUiThread(() -> {
            List<ProductDetails> list = details.getProductDetailsList();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) {
                product = list.get(0);
                onChange.run();
            }
            if (then != null) then.run();
        }));
    }

    void buyRemoveAds() {
        connect(() -> {
            if (product != null) { launchPurchase(); return; }
            // The product may not have loaded at launch (offline, or just created in Play Console): ask again.
            queryProduct(() -> {
                if (product != null) launchPurchase();
                else say("Remove ads isn't available yet. Purchases only work when the app is installed from Google Play.");
            });
        }, true);
    }

    private void launchPurchase() {
        BillingFlowParams params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(Collections.singletonList(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build();
        BillingResult result = billing.launchBillingFlow(activity, params);
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) checkOwned(true);
        else if (code != BillingClient.BillingResponseCode.OK && code != BillingClient.BillingResponseCode.USER_CANCELED)
            say("Couldn't open the Google Play purchase screen. Try again later.");
    }

    void restore() { connect(() -> checkOwned(true), true); }

    private void checkOwned(boolean report) {
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (result, purchases) -> activity.runOnUiThread(() -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    if (report) say("Couldn't reach Google Play. Try again later.");
                    return;
                }
                boolean owned = handle(purchases);
                // Refunds and revoked purchases disappear from this list, so ads come back.
                if (!owned && adsRemoved()) setAdsRemoved(false);
                if (report) say(owned ? "Purchase restored. Ads are off." : "No purchase found for this Google account.");
            }));
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        activity.runOnUiThread(() -> {
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases);
            else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) checkOwned(true);
            else if (code != BillingClient.BillingResponseCode.USER_CANCELED) say("The purchase didn't go through.");
        });
    }

    // Grants the product for completed purchases and acknowledges them (Play refunds unacknowledged ones after 3 days).
    private boolean handle(List<Purchase> purchases) {
        boolean owned = false;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) { say("Your purchase is pending. Ads turn off once it completes."); continue; }
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            owned = true;
            if (!adsRemoved()) setAdsRemoved(true);
            if (!p.isAcknowledged()) {
                billing.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                    ackResult -> {});
            }
        }
        return owned;
    }

    private void setAdsRemoved(boolean removed) {
        prefs.edit().putBoolean("adsRemoved", removed).apply();
        if (removed) interstitial = null;
        else if (adsStarted.get()) loadInterstitial();
        else startAds();
        onChange.run();
    }

    private void say(String text) { message = text; onChange.run(); }

    // State for the page's settings sheet; a message is delivered once. Main thread only.
    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("available", true);
            o.put("adsRemoved", adsRemoved());
            o.put("price", product != null && product.getOneTimePurchaseOfferDetails() != null
                ? product.getOneTimePurchaseOfferDetails().getFormattedPrice() : JSONObject.NULL);
            o.put("privacyOptions", consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED);
            if (message != null) o.put("message", message);
        } catch (JSONException ignored) {}
        message = null;
        return o.toString();
    }

    void destroy() { if (billing != null) billing.endConnection(); }
}
