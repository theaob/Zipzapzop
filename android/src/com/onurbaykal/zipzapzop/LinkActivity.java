package com.onurbaykal.zipzapzop;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

// Opens a shared Daily: https://theaob.github.io/Zipzapzop/daily/?d=2026-11-09&p=hard (an App Link, see
// docs/daily/) or zipzapzop://daily?d=...&p=... (the link page's "Open in the app" button). It has no screen:
// it hands the day and difficulty to the game, reusing the running game if there is one, and closes.
public class LinkActivity extends Activity {
    static final String EXTRA_LINK = "zzz.link";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent game = new Intent(this, MainActivity.class);
        String link = parse(getIntent() != null ? getIntent().getData() : null);
        if (link != null) game.putExtra(EXTRA_LINK, link);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(game);
        finish();
    }

    // "2026-11-09|hard", or null when the link doesn't name a valid day and difficulty. The page checks the
    // day again (archive start, not in the future); this only keeps what reaches JavaScript to a safe shape.
    static String parse(Uri uri) {
        if (uri == null) return null;
        String d, p;
        try { d = uri.getQueryParameter("d"); p = uri.getQueryParameter("p"); }
        catch (UnsupportedOperationException e) { return null; }
        if (d == null || !d.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        if (p == null || !p.matches("easy|medium|hard")) p = "";
        return d + "|" + p;
    }
}
