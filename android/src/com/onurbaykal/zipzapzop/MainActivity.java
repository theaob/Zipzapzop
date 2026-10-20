package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

// Hosts the ZipZapZop web game, bundled under assets/, in a full-screen WebView.
public class MainActivity extends Activity {
    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_NOTIFY = 1;
    private WebView web;
    private int pendingHour, pendingMinute;
    private Monetization money;
    private Telemetry telemetry;
    private volatile String adsState; // read by the page from the JavaScript thread

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(getResources().getColor(R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // best times and daily results live in localStorage
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "ZipZapZopAndroid");
        // From targetSdk 35 the app draws behind the system bars; pad the game clear of them.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getResources().getColor(R.color.bg));
        root.addView(web);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
        setContentView(root);
        telemetry = new Telemetry(this);
        telemetry.refreshConsent();
        money = new Monetization(this, new Runnable() {
            @Override
            public void run() { pushAdsState(); }
        });
        adsState = money.stateJson();
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
        Reminder.schedule(this); // alarms are dropped when the app is force-stopped
        money.start();
    }

    // Sends the ads/purchase state to the page's settings sheet. Main thread only.
    private void pushAdsState() {
        telemetry.refreshConsent(); // consent changes arrive the same way
        adsState = money.stateJson();
        web.evaluateJavascript("window.zzzAdsState && window.zzzAdsState(" + adsState + ")", null);
    }

    @Override
    protected void onDestroy() {
        money.destroy();
        super.onDestroy();
    }

    private void onUi(Runnable r) { runOnUiThread(r); }

    // Turning the reminder on asks for the notification permission first on Android 13+.
    private void setReminder(boolean on, int hour, int minute) {
        if (on && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(NOTIFY_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pendingHour = hour; pendingMinute = minute;
            requestPermissions(new String[] { NOTIFY_PERMISSION }, REQ_NOTIFY);
            return;
        }
        Reminder.configure(this, on, hour, minute);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQ_NOTIFY) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Reminder.configure(this, granted, pendingHour, pendingMinute);
        if (!granted) web.evaluateJavascript("window.zzzReminderDenied && window.zzzReminderDenied()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    // Exposed to the page as window.ZipZapZopAndroid: sharing (WebView has no Web Share API), the daily reminder,
    // ads plus the remove-ads purchase, and analytics.
    private class Bridge {
        @JavascriptInterface
        public void setReminder(final boolean on, final int hour, final int minute) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { MainActivity.this.setReminder(on, hour, minute); }
            });
        }

        @JavascriptInterface
        public String adsState() { return adsState; }

        @JavascriptInterface
        public void puzzleFinished(final String mode) {
            onUi(new Runnable() { @Override public void run() { money.puzzleFinished(mode); } });
        }

        @JavascriptInterface
        public void buyRemoveAds() {
            onUi(new Runnable() { @Override public void run() { money.buyRemoveAds(); } });
        }

        @JavascriptInterface
        public void restorePurchase() {
            onUi(new Runnable() { @Override public void run() { money.restore(); } });
        }

        @JavascriptInterface
        public void privacyOptions() {
            onUi(new Runnable() { @Override public void run() { money.showPrivacyOptions(); } });
        }

        // Gameplay events and JavaScript errors for Analytics and Crashlytics (only with consent).
        @JavascriptInterface
        public void track(final String name, final String params) {
            onUi(new Runnable() { @Override public void run() { telemetry.event(name, params); } });
        }

        @JavascriptInterface
        public void pageError(final String message) {
            onUi(new Runnable() { @Override public void run() { telemetry.pageError(message); } });
        }

        @JavascriptInterface
        public void dailiesDone(String date) { Reminder.markAllDone(MainActivity.this, date); }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, "Share result"));
                }
            });
        }
    }
}
