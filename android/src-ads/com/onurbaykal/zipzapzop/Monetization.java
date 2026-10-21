package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.SharedPreferences;

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
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// AdMob rewarded ads for extra hints and Ladder energy (only when the player asks for one), Google's
// consent flow (UMP), and the one-time "remove_ads" Play purchase. Every callback that touches state runs on the main thread.
final class Monetization {
    static final String PRODUCT = "remove_ads";

    private final Activity activity;
    private final Runnable onChange;
    private final RewardedAdListener onRewarded;
    private final SharedPreferences prefs;
    private final ConsentInformation consent;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private BillingClient billing;
    private ProductDetails product;
    private RewardedAd rewarded;
    private boolean loadingRewarded;
    private String message;

    Monetization(Activity activity, Runnable onChange, RewardedAdListener onRewarded) {
        this.activity = activity;
        this.onChange = onChange;
        this.onRewarded = onRewarded;
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
        MobileAds.initialize(activity, status -> loadRewarded());
    }

    private void loadRewarded() {
        if (loadingRewarded || rewarded != null || adsRemoved() || !consent.canRequestAds()) return;
        loadingRewarded = true;
        RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED, new AdRequest.Builder().build(),
            new RewardedAdLoadCallback() {
                @Override
                public void onAdLoaded(RewardedAd ad) { loadingRewarded = false; rewarded = ad; }

                @Override
                public void onAdFailedToLoad(LoadAdError error) { loadingRewarded = false; } // retried on the next request
            });
    }

    // The player chose to watch an ad for a reward (`purpose`: "hint" or "energy"). Reports "reward",
    // "closed" (left before the reward) or "unavailable" (no ad loaded yet, offline, or no consent to request ads).
    void watchRewardedAd(final String purpose) {
        if (rewarded == null) { onRewarded.rewardedAd(purpose, "unavailable"); startAds(); loadRewarded(); return; }
        RewardedAd ad = rewarded;
        rewarded = null;
        final boolean[] earned = { false };
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                if (!earned[0]) onRewarded.rewardedAd(purpose, "closed");
                loadRewarded();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) {
                onRewarded.rewardedAd(purpose, "unavailable");
                loadRewarded();
            }
        });
        ad.show(activity, reward -> { earned[0] = true; onRewarded.rewardedAd(purpose, "reward"); });
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
                            ? "billing_unavailable"
                            : "no_connection");
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
                else say("store_unavailable");
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
            say("purchase_screen");
    }

    void restore() { connect(() -> checkOwned(true), true); }

    private void checkOwned(boolean report) {
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (result, purchases) -> activity.runOnUiThread(() -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    if (report) say("play_unreachable");
                    return;
                }
                boolean owned = handle(purchases);
                // Refunds and revoked purchases disappear from this list, so ads come back.
                if (!owned && adsRemoved()) setAdsRemoved(false);
                if (report) say(owned ? "restored" : "no_purchase");
            }));
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        activity.runOnUiThread(() -> {
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases);
            else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) checkOwned(true);
            else if (code != BillingClient.BillingResponseCode.USER_CANCELED) say("purchase_failed");
        });
    }

    // Grants the product for completed purchases and acknowledges them (Play refunds unacknowledged ones after 3 days).
    private boolean handle(List<Purchase> purchases) {
        boolean owned = false;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) { say("pending"); continue; }
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
        if (removed) rewarded = null;
        else if (adsStarted.get()) loadRewarded();
        else startAds();
        onChange.run();
    }

    // Messages are keys; the page shows them in the player's language (msg_<key> in its STRINGS).
    private void say(String key) { message = key; onChange.run(); }

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
