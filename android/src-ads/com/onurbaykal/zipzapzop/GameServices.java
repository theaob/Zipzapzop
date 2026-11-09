package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.games.AuthenticationResult;
import com.google.android.gms.games.GameStatsClient;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.PlayGamesSdk;
import com.google.android.gms.games.playergameevent.PlayerGameEvent;
import com.google.android.gms.tasks.Task;

import org.json.JSONObject;

import java.util.Iterator;

// Google Play Games Services v2: automatic sign-in, achievements, leaderboards and Game Stats. Their IDs come from
// res/values/games-ids.xml, which CI writes from the PLAY_GAMES_RESOURCES secret (Play Console's "Get resources"
// export). Without that file Play Games stays off; an achievement or leaderboard missing from it is skipped.
final class GameServices {
    private static final String TAG = "ZipZapZopGames";
    private static final int REQ_UI = 9001;
    // After the player closes the launch sign-in prompt, launches stay quiet this long; Settings' Sign in still works.
    private static final long QUIET_AFTER_DISMISS_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String PREF_DISMISSED = "games_prompt_dismissed_at";
    private final Activity activity;
    private final Runnable onChange;
    private final boolean available;
    private final SharedPreferences prefs;
    private boolean signedIn;
    private boolean launched; // the launch check has run in this activity
    private boolean busy;     // a sign-in check or prompt is in flight

    GameServices(Activity activity, Runnable onChange) {
        this.activity = activity;
        this.onChange = onChange;
        this.available = !activity.getString(R.string.zzz_games_app_id).isEmpty();
        this.prefs = activity.getSharedPreferences("games", Activity.MODE_PRIVATE);
        if (available) PlayGamesSdk.initialize(activity);
        else Log.i(TAG, "No Play Games project ID in this build (games-ids.xml missing), so Play Games is off");
    }

    // Called from onResume. On launch Play Games signs returning players in by itself; this reads the outcome, and if
    // the player isn't signed in it opens the sign-in prompt once, without waiting for the Settings button. Later
    // resumes only re-read the state, so a sign-in made in the Play Games app shows up when the player comes back.
    void onResume() {
        if (!available || busy || signedIn) return;
        final boolean first = !launched;
        launched = true;
        busy = true;
        PlayGames.getGamesSignInClient(activity).isAuthenticated().addOnCompleteListener(activity, t -> {
            busy = false;
            update(t, "isAuthenticated");
            if (first && !signedIn && mayPromptAtLaunch()) prompt(true);
        });
    }

    // The Sign in button in Settings, for players who closed the prompt or whose automatic sign-in didn't go through.
    void signIn() {
        if (available && !busy) prompt(false);
    }

    private void prompt(boolean atLaunch) {
        busy = true;
        PlayGames.getGamesSignInClient(activity).signIn().addOnCompleteListener(activity, t -> {
            busy = false;
            update(t, "signIn");
            if (atLaunch && !signedIn) prefs.edit().putLong(PREF_DISMISSED, System.currentTimeMillis()).apply();
            if (signedIn) prefs.edit().remove(PREF_DISMISSED).apply();
        });
    }

    private boolean mayPromptAtLaunch() {
        long at = prefs.getLong(PREF_DISMISSED, 0);
        long now = System.currentTimeMillis();
        return at == 0 || at > now || now - at >= QUIET_AFTER_DISMISS_MS;
    }

    // Records the outcome and tells the page. Failures go to logcat (adb logcat -s ZipZapZopGames) with Play Games'
    // status code, the quickest way to tell a missing tester account from a signing certificate (SHA-1) that has no
    // Android credential in the Play Games project.
    private void update(Task<AuthenticationResult> t, String what) {
        signedIn = t.isSuccessful() && t.getResult() != null && t.getResult().isAuthenticated();
        if (!t.isSuccessful()) {
            Exception e = t.getException();
            String code = e instanceof ApiException ? " (status " + ((ApiException) e).getStatusCode() + ")" : "";
            Log.w(TAG, what + " failed" + code, e);
        } else if (!signedIn) {
            Log.i(TAG, what + ": not signed in");
        }
        onChange.run();
    }

    String stateJson() {
        return "{\"available\":" + available + ",\"signedIn\":" + signedIn + "}";
    }

    // key: the achievement's resource name without "achievement_", e.g. "meadow_complete".
    void unlock(String key) {
        String id = id("achievement_" + key);
        if (id != null) PlayGames.getAchievementsClient(activity).unlock(id);
    }

    // key: the leaderboard's resource name without "leaderboard_", e.g. "ladder".
    void submitScore(String key, long score) {
        String id = id("leaderboard_" + key);
        if (id != null) PlayGames.getLeaderboardsClient(activity).submitScore(id, score);
    }

    // Game Stats: one gameplay event (e.g. "puzzleSolved" or "progressUpdate") with its properties as a JSON object
    // of strings, whole numbers and booleans. Play Games adds them up into the stats set in Play Console, matching
    // events by name, so no resource IDs are needed. Events that don't match the console's schema are dropped there.
    void recordEvent(String name, String propsJson) {
        if (!signedIn || !name.matches("[A-Za-z0-9_]{1,50}")) return;
        try {
            JSONObject props = new JSONObject(propsJson);
            PlayerGameEvent.Builder event = new PlayerGameEvent.Builder(name);
            for (Iterator<String> it = props.keys(); it.hasNext(); ) {
                String key = it.next();
                Object v = props.get(key);
                if (v instanceof Boolean) event.addProperty(key, (boolean) (Boolean) v);
                else if (v instanceof Number) event.addProperty(key, ((Number) v).longValue());
                else if (v instanceof String) event.addProperty(key, (String) v);
            }
            GameStatsClient stats = PlayGames.getGameStatsClient(activity);
            stats.recordEvent(event.build());
            stats.requestEventsUpload();
        } catch (Exception ignored) {
            // A malformed event from the page is skipped; it must never take the game down.
        }
    }

    void showAchievements() {
        if (signedIn) show(PlayGames.getAchievementsClient(activity).getAchievementsIntent());
    }

    void showLeaderboards() {
        if (signedIn) show(PlayGames.getLeaderboardsClient(activity).getAllLeaderboardsIntent());
    }

    private void show(Task<Intent> task) {
        task.addOnSuccessListener(activity, intent -> activity.startActivityForResult(intent, REQ_UI));
    }

    @SuppressWarnings("DiscouragedApi") // the IDs are looked up by name: which ones exist depends on the export
    private String id(String name) {
        if (!signedIn || !name.matches("[a-z0-9_]+")) return null;
        int res = activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        return res == 0 ? null : activity.getString(res);
    }
}
