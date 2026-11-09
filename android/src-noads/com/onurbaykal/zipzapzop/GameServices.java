package com.onurbaykal.zipzapzop;

import android.app.Activity;

// Stand-in used by build-apk.sh, which can't fetch Play Games. The Gradle build uses src-ads/ instead.
final class GameServices {
    GameServices(Activity activity, Runnable onChange) {}

    void onResume() {}
    void signIn() {}
    String stateJson() { return "{\"available\":false,\"signedIn\":false}"; }
    void unlock(String key) {}
    void submitScore(String key, long score) {}
    void recordEvent(String name, String propsJson) {}
    void showAchievements() {}
    void showLeaderboards() {}
}
