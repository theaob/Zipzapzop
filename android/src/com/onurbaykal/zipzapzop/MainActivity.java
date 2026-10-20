package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Hosts the ZipZapZop web game, bundled under assets/, in a full-screen WebView.
public class MainActivity extends Activity {
    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_NOTIFY = 1;
    private WebView web;
    private int pendingHour, pendingMinute;

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
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
        Reminder.schedule(this); // alarms are dropped when the app is force-stopped
    }

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

    // Exposed to the page as window.ZipZapZopAndroid: sharing (WebView has no Web Share API) and the daily reminder.
    private class Bridge {
        @JavascriptInterface
        public void setReminder(final boolean on, final int hour, final int minute) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { MainActivity.this.setReminder(on, hour, minute); }
            });
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
