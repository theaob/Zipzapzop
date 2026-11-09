package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
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
    private GameServices games;
    private volatile String gamesState;
    private BackHandler back;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lockPhonePortrait();
        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(getResources().getColor(R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // best times and daily results live in localStorage
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            // Web links (privacy policy, terms) open in the browser instead of replacing the game.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (ActivityNotFoundException e) { }
                return true;
            }
        });
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
        }, new RewardedAdListener() {
            @Override
            public void rewardedAd(String purpose, String result) {
                web.evaluateJavascript("window.zzzRewardedAd && window.zzzRewardedAd('" + purpose + "', '" + result + "')", null);
            }
        });
        adsState = money.stateJson();
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
        Reminder.schedule(this); // alarms are dropped when the app is force-stopped
        money.start();
        games = new GameServices(this, new Runnable() {
            @Override
            public void run() { pushGamesState(); }
        });
        gamesState = games.stateJson(); // signs in from onResume, which runs right after this on launch
        back = new BackHandler(this, new Runnable() {
            @Override
            public void run() { handleBack(); }
        });
    }

    // Portrait on a phone's own screen. On an Android Auto car screen (another display, landscape or portrait) and on
    // tablets the page lays itself out for any shape, and Android Auto rejects games pillarboxed on landscape screens.
    // Moving to another display recreates the activity, so this runs again there.
    @SuppressWarnings("deprecation") // getDefaultDisplay(): Activity.getDisplay() needs API 30, the local SDK is 23
    private void lockPhonePortrait() {
        boolean ownScreen = getWindowManager().getDefaultDisplay().getDisplayId() == Display.DEFAULT_DISPLAY;
        boolean phone = getResources().getConfiguration().smallestScreenWidthDp < 600;
        setRequestedOrientation(ownScreen && phone
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // Back goes to the page first: it closes Settings, or asks for a second press before exiting.
    private void handleBack() {
        web.evaluateJavascript("window.zzzBack ? zzzBack() : 'exit'", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String result) {
                if ("\"exit\"".equals(result)) finish();
            }
        });
    }

    @Override
    public void onBackPressed() { handleBack(); } // Android 12 and older; newer versions use BackHandler

    // Sends the ads/purchase state to the page's settings sheet. Main thread only.
    private void pushAdsState() {
        telemetry.refreshConsent(); // consent changes arrive the same way
        adsState = money.stateJson();
        web.evaluateJavascript("window.zzzAdsState && window.zzzAdsState(" + adsState + ")", null);
    }

    // Sends the Play Games sign-in state to the page, which then resends achievements and scores. Main thread only.
    private void pushGamesState() {
        gamesState = games.stateJson();
        web.evaluateJavascript("window.zzzGamesState && window.zzzGamesState(" + gamesState + ")", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        games.onResume();
    }

    @Override
    protected void onDestroy() {
        back.destroy();
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
    // ads (rewarded ones for extra hints and energy) plus the remove-ads and Endless purchases, analytics, and Play Games.
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

        // "1.0 (24)": version name and build number, for the Settings sheet.
        @JavascriptInterface
        @SuppressWarnings("deprecation") // versionCode: the local SDK predates getLongVersionCode()
        public String appVersion() {
            try {
                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return info.versionName + " (" + info.versionCode + ")";
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void watchRewardedAd(final String purpose) {
            // Only known purposes, since it is echoed back into JavaScript.
            if (!"hint".equals(purpose) && !"energy".equals(purpose)) return;
            onUi(new Runnable() { @Override public void run() { money.watchRewardedAd(purpose); } });
        }

        @JavascriptInterface
        public void buyRemoveAds() {
            onUi(new Runnable() { @Override public void run() { money.buyRemoveAds(); } });
        }

        @JavascriptInterface
        public void buyEndless() {
            onUi(new Runnable() { @Override public void run() { money.buyEndless(); } });
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

        // Google Play Games: sign-in state, achievements, leaderboard scores, Game Stats events and Play Games' own screens.
        @JavascriptInterface
        public String gamesState() { return gamesState; }

        @JavascriptInterface
        public void gamesSignIn() {
            onUi(new Runnable() { @Override public void run() { games.signIn(); } });
        }

        @JavascriptInterface
        public void gamesUnlock(final String key) {
            onUi(new Runnable() { @Override public void run() { games.unlock(key); } });
        }

        @JavascriptInterface
        public void gamesScore(final String key, final double score) {
            onUi(new Runnable() { @Override public void run() { games.submitScore(key, (long) score); } });
        }

        @JavascriptInterface
        public void gamesEvent(final String name, final String propsJson) {
            onUi(new Runnable() { @Override public void run() { games.recordEvent(name, propsJson); } });
        }

        @JavascriptInterface
        public void gamesShowAchievements() {
            onUi(new Runnable() { @Override public void run() { games.showAchievements(); } });
        }

        @JavascriptInterface
        public void gamesShowLeaderboards() {
            onUi(new Runnable() { @Override public void run() { games.showLeaderboards(); } });
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
