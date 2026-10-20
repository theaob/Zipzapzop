package com.onurbaykal.zipzapzop;

import android.content.Context;
import android.os.Bundle;

import com.google.android.ump.ConsentInformation;
import com.google.android.ump.UserMessagingPlatform;
import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONObject;

import java.util.Iterator;

// Firebase Analytics (gameplay events) and Crashlytics (crashes, plus JavaScript errors from the page).
// Both stay off until Google's consent flow says they may run: consent obtained, or not required where
// the player lives. Builds without google-services.json have no Firebase app, and this does nothing.
final class Telemetry {
    private final Context context;
    private final boolean available;
    private boolean enabled;

    Telemetry(Context context) {
        this.context = context.getApplicationContext();
        this.available = !FirebaseApp.getApps(this.context).isEmpty();
    }

    // Call whenever the consent status may have changed.
    void refreshConsent() {
        if (!available) return;
        int status = UserMessagingPlatform.getConsentInformation(context).getConsentStatus();
        boolean allowed = status == ConsentInformation.ConsentStatus.NOT_REQUIRED
            || status == ConsentInformation.ConsentStatus.OBTAINED;
        if (allowed == enabled) return;
        enabled = allowed;
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(allowed);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(allowed);
    }

    // name: a Firebase event name; params: a flat JSON object of strings and numbers.
    void event(String name, String params) {
        if (!available || !enabled) return;
        Bundle b = new Bundle();
        try {
            JSONObject o = new JSONObject(params == null ? "{}" : params);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (d == Math.rint(d)) b.putLong(k, (long) d); else b.putDouble(k, d);
                } else {
                    b.putString(k, String.valueOf(v));
                }
            }
        } catch (Exception ignored) {}
        FirebaseAnalytics.getInstance(context).logEvent(name, b);
    }

    void pageError(String message) {
        if (!available || !enabled) return;
        FirebaseCrashlytics.getInstance().recordException(new PageError(message));
    }

    private static final class PageError extends Exception {
        PageError(String message) { super(message); }
    }
}
