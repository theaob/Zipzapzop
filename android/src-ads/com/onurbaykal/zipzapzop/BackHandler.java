package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

// Routes Back to the page on Android 13+, where predictive back replaces onBackPressed().
// Lives beside the Google SDK code because the local build's older SDK lacks these classes.
final class BackHandler {
    private final Activity activity;
    private OnBackInvokedCallback callback;

    BackHandler(Activity activity, final Runnable onBack) {
        this.activity = activity;
        if (Build.VERSION.SDK_INT < 33) return;
        callback = onBack::run;
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
    }

    void destroy() {
        if (callback != null) activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
    }
}
