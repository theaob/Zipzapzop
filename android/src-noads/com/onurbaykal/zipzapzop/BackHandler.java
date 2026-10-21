package com.onurbaykal.zipzapzop;

import android.app.Activity;

// Stand-in used by build-apk.sh: that build targets API 34 without predictive back, so
// onBackPressed() still receives every Back press.
final class BackHandler {
    BackHandler(Activity activity, Runnable onBack) {}

    void destroy() {}
}
