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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// AdMob rewarded ads for extra hints and Ladder energy (only when the player asks for one), Google's
// consent flow (UMP), and two one-time Play purchases: "remove_ads" and "endless" (unlocks Endless mode).
// Every callback that touches state runs on the main thread.
final class Monetization {
    static final String PRODUCT = "remove_ads";
    static final String ENDLESS = "endless";

    private final Activity activity;
    private final Runnable onChange;
    private final RewardedAdListener onRewarded;
    private final SharedPreferences prefs;
    private final ConsentInformation consent;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private BillingClient billing;
    private final Map<String, ProductDetails> products = new HashMap<>();
    // One rewarded ad unit per purpose, so AdMob reports hint and energy ads separately.
    private final Map<String, RewardedAd> rewarded = new HashMap<>();
    private final Set<String> loadingRewarded = new HashSet<>();
    private String message;

    Monetization(Activity activity, Runnable onChange, RewardedAdListener onRewarded) {
        this.activity = activity;
        this.onChange = onChange;
        this.onRewarded = onRewarded;
        this.prefs = activity.getSharedPreferences("monetization", Activity.MODE_PRIVATE);
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    private boolean adsRemoved() { return prefs.getBoolean("adsRemoved", false); }
    private boolean endlessOwned() { return prefs.getBoolean("endlessOwned", false); }

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
        MobileAds.initialize(activity, status -> loadAllRewarded());
    }

    private static String unitFor(String purpose) {
        return "energy".equals(purpose) ? BuildConfig.ADMOB_REWARDED_ENERGY : BuildConfig.ADMOB_REWARDED_HINT;
    }

    private void loadAllRewarded() { loadRewarded("hint"); loadRewarded("energy"); }

    private void loadRewarded(final String purpose) {
        if (loadingRewarded.contains(purpose) || rewarded.containsKey(purpose) || adsRemoved() || !consent.canRequestAds()) return;
        loadingRewarded.add(purpose);
        RewardedAd.load(activity, unitFor(purpose), new AdRequest.Builder().build(),
            new RewardedAdLoadCallback() {
                @Override
                public void onAdLoaded(RewardedAd ad) { loadingRewarded.remove(purpose); rewarded.put(purpose, ad); }

                @Override
                public void onAdFailedToLoad(LoadAdError error) { loadingRewarded.remove(purpose); } // retried on the next request
            });
    }

    // The player chose to watch an ad for a reward (`purpose`: "hint" or "energy"). Reports "reward",
    // "closed" (left before the reward) or "unavailable" (no ad loaded yet, offline, or no consent to request ads).
    void watchRewardedAd(final String purpose) {
        RewardedAd ad = rewarded.remove(purpose);
        if (ad == null) { onRewarded.rewardedAd(purpose, "unavailable"); startAds(); loadRewarded(purpose); return; }
        final boolean[] earned = { false };
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                if (!earned[0]) onRewarded.rewardedAd(purpose, "closed");
                loadRewarded(purpose);
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) {
                onRewarded.rewardedAd(purpose, "unavailable");
                loadRewarded(purpose);
            }
        });
        ad.show(activity, reward -> { earned[0] = true; onRewarded.rewardedAd(purpose, "reward"); });
    }

    void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> onChange.run());
    }

    // ---- Play Billing: two non-consumable products ----

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

    // Loads both products and their local prices; `then` runs afterwards whether or not they were found.
    private void queryProduct(Runnable then) {
        List<QueryProductDetailsParams.Product> ids = new java.util.ArrayList<>();
        for (String id : new String[] { PRODUCT, ENDLESS })
            ids.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(BillingClient.ProductType.INAPP).build());
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder().setProductList(ids).build();
        billing.queryProductDetailsAsync(params, (result, details) -> activity.runOnUiThread(() -> {
            List<ProductDetails> list = details.getProductDetailsList();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) {
                for (ProductDetails d : list) products.put(d.getProductId(), d);
                onChange.run();
            }
            if (then != null) then.run();
        }));
    }

    void buyRemoveAds() { buy(PRODUCT); }

    void buyEndless() { buy(ENDLESS); }

    private void buy(final String id) {
        connect(() -> {
            if (products.containsKey(id)) { launchPurchase(products.get(id)); return; }
            // The product may not have loaded at launch (offline, or just created in Play Console): ask again.
            queryProduct(() -> {
                if (products.containsKey(id)) launchPurchase(products.get(id));
                else say("store_unavailable");
            });
        }, true);
    }

    private void launchPurchase(ProductDetails product) {
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
                Set<String> owned = handle(purchases);
                // Refunds and revoked purchases disappear from this list, so ads come back and Endless locks again.
                if (!owned.contains(PRODUCT) && adsRemoved()) setAdsRemoved(false);
                if (!owned.contains(ENDLESS) && endlessOwned()) setEndlessOwned(false);
                if (report) say(owned.contains(PRODUCT) ? "restored" : owned.contains(ENDLESS) ? "restored_endless" : "no_purchase");
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

    // Grants the products of completed purchases and acknowledges them (Play refunds unacknowledged ones after 3 days).
    // Returns the ids of the products owned.
    private Set<String> handle(List<Purchase> purchases) {
        Set<String> owned = new HashSet<>();
        for (Purchase p : purchases) {
            boolean ads = p.getProducts().contains(PRODUCT), endless = p.getProducts().contains(ENDLESS);
            if (!ads && !endless) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) { say(ads ? "pending" : "pending_endless"); continue; }
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            if (ads) { owned.add(PRODUCT); if (!adsRemoved()) setAdsRemoved(true); }
            if (endless) { owned.add(ENDLESS); if (!endlessOwned()) setEndlessOwned(true); }
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
        if (removed) rewarded.clear();
        else if (adsStarted.get()) loadAllRewarded();
        else startAds();
        onChange.run();
    }

    private void setEndlessOwned(boolean owned) {
        prefs.edit().putBoolean("endlessOwned", owned).apply();
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
            o.put("price", priceOf(PRODUCT));
            o.put("endlessOwned", endlessOwned());
            o.put("endlessPrice", priceOf(ENDLESS));
            o.put("privacyOptions", consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED);
            if (message != null) o.put("message", message);
        } catch (JSONException ignored) {}
        message = null;
        return o.toString();
    }

    private Object priceOf(String id) {
        ProductDetails d = products.get(id);
        return d != null && d.getOneTimePurchaseOfferDetails() != null
            ? d.getOneTimePurchaseOfferDetails().getFormattedPrice() : JSONObject.NULL;
    }

    void destroy() { if (billing != null) billing.endConnection(); }
}
