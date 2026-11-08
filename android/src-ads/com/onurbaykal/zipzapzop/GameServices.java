package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.Intent;

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
    private static final int REQ_UI = 9001;
    private final Activity activity;
    private final Runnable onChange;
    private final boolean available;
    private boolean signedIn;

    GameServices(Activity activity, Runnable onChange) {
        this.activity = activity;
        this.onChange = onChange;
        this.available = !activity.getString(R.string.zzz_games_app_id).isEmpty();
        if (available) PlayGamesSdk.initialize(activity);
    }

    // Play Games signs returning players in by itself; this reads the outcome.
    void start() {
        if (available) watch(PlayGames.getGamesSignInClient(activity).isAuthenticated());
    }

    // For players who skipped or dismissed the automatic sign-in.
    void signIn() {
        if (available) watch(PlayGames.getGamesSignInClient(activity).signIn());
    }

    private void watch(Task<AuthenticationResult> task) {
        task.addOnCompleteListener(activity, t -> {
            signedIn = t.isSuccessful() && t.getResult().isAuthenticated();
            onChange.run();
        });
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
