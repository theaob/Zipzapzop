package com.onurbaykal.zipzapzop;

import android.content.Context;

// Stand-in used by build-apk.sh, which can't fetch Firebase. The Gradle build uses src-ads/ instead.
final class Telemetry {
    Telemetry(Context context) {}

    void refreshConsent() {}
    void event(String name, String params) {}
    void pageError(String message) {}
}
