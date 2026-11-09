package com.onurbaykal.zipzapzop;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Daily challenge reminder and the evening "your streak ends tonight" nudge: local notifications
// scheduled with AlarmManager and re-armed after each firing, reboot, time change or app update.
// Nothing leaves the device. Built against the API 23 SDK, so newer calls (notification channels)
// use reflection.
//
// The daily reminder goes out at the time the player picked or, with "at my usual time" on, a little
// before the time they usually solve their first Daily (learned from the last two weeks once there are
// a few days to go on). The nudge goes out in the evening on a day that would break a streak of 2+
// days, unless a Daily was solved that day or a streak freeze would cover it.
final class Reminder {
    static final String ACTION_FIRE = "com.onurbaykal.zipzapzop.REMINDER";
    static final String ACTION_NUDGE = "com.onurbaykal.zipzapzop.STREAK_NUDGE";
    private static final String PREFS = "reminder";
    private static final String CHANNEL = "daily";
    private static final int NOTIFICATION_ID = 1;

    // Usual-time learning: the first Daily solve of each day, the last PLAY_DAYS days, needing MIN_DAYS.
    private static final int PLAY_DAYS = 14;
    private static final int MIN_DAYS = 3;
    private static final int LEAD_MINUTES = 15; // remind a little before they usually sit down to play
    private static final int EARLIEST = 8 * 60, LATEST = 21 * 60;
    // Nudge: 20:00, or two hours after the daily reminder if that is later, but never after 22:00.
    private static final int NUDGE_AT = 20 * 60, NUDGE_GAP = 120, NUDGE_LATEST = 22 * 60;
    private static final int MIN_STREAK = 2;

    private Reminder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String today() {
        return day(Calendar.getInstance());
    }

    private static String day(Calendar cal) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    static void configure(Context c, boolean enabled, int hour, int minute) {
        prefs(c).edit().putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute).apply();
        schedule(c);
    }

    // "At my usual time" and the streak nudge, sent by the page at launch and whenever they change.
    static void options(Context c, boolean usualTime, boolean streakNudge) {
        prefs(c).edit().putBoolean("auto", usualTime).putBoolean("nudge", streakNudge).apply();
        schedule(c);
    }

    // The page reports when all of a day's dailies are solved, so that day's reminder is skipped.
    static void markAllDone(Context c, String date) {
        prefs(c).edit().putString("allDone", date).apply();
        cancelNotification(c);
    }

    // A Daily was solved just now: learn the time of day from the first solve of each day.
    static void dailySolved(Context c) {
        SharedPreferences p = prefs(c);
        String now = today();
        List<String> plays = plays(p);
        if (!plays.isEmpty() && plays.get(plays.size() - 1).startsWith(now + "@")) return;
        Calendar cal = Calendar.getInstance();
        plays.add(now + "@" + (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)));
        while (plays.size() > PLAY_DAYS) plays.remove(0);
        p.edit().putString("plays", join(plays)).apply();
        schedule(c);
    }

    // Streak state from the page: on riskDay the streak ends unless a Daily is solved (or `covered`: a
    // freeze would bridge it). Solving moves riskDay on, so the nudge never fires after that day's solve.
    static void streakState(Context c, String riskDay, int streak, boolean covered) {
        prefs(c).edit().putString("riskDay", riskDay).putInt("streak", streak).putBoolean("covered", covered).apply();
        // A nudge that is showing goes away once the streak is safe.
        if (!nudgeDue(c) && prefs(c).getBoolean("nudgeShown", false)) {
            cancelNotification(c);
            prefs(c).edit().putBoolean("nudgeShown", false).apply();
        }
    }

    // Minutes after midnight the daily reminder goes out at.
    static int reminderMinute(Context c) {
        SharedPreferences p = prefs(c);
        int picked = p.getInt("hour", 9) * 60 + p.getInt("minute", 0);
        if (!p.getBoolean("auto", false)) return picked;
        int usual = usualMinute(p);
        return usual < 0 ? picked : usual;
    }

    // "HH:MM" of the learned reminder time, or "" while there aren't enough days yet. Shown in Settings.
    static String usualTime(Context c) {
        int m = usualMinute(prefs(c));
        return m < 0 ? "" : String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    // Circular mean of the first-solve times (so 23:50 and 00:10 average to midnight, not noon),
    // minus a short lead, rounded to 5 minutes and kept to waking hours. -1 with too few days.
    private static int usualMinute(SharedPreferences p) {
        List<String> plays = plays(p);
        if (plays.size() < MIN_DAYS) return -1;
        double x = 0, y = 0;
        for (String s : plays) {
            double a = Integer.parseInt(s.substring(s.indexOf('@') + 1)) / 1440.0 * 2 * Math.PI;
            x += Math.cos(a); y += Math.sin(a);
        }
        double a = Math.atan2(y, x);
        if (a < 0) a += 2 * Math.PI;
        int m = (int) Math.round(a / (2 * Math.PI) * 1440) - LEAD_MINUTES;
        m = ((m % 1440) + 1440) % 1440;
        m = Math.round(m / 5f) * 5;
        return Math.max(EARLIEST, Math.min(LATEST, m));
    }

    private static int nudgeMinute(Context c) {
        return Math.min(NUDGE_LATEST, Math.max(NUDGE_AT, reminderMinute(c) + NUDGE_GAP));
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent daily = alarmIntent(c, ACTION_FIRE, 0), nudge = alarmIntent(c, ACTION_NUDGE, 1);
        am.cancel(daily);
        am.cancel(nudge);
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("enabled", false)) return;
        // A day whose reminder already went out isn't reminded twice when the learned time moves later.
        arm(am, daily, reminderMinute(c), p.getString("firedOn", ""));
        if (p.getBoolean("nudge", true)) arm(am, nudge, nudgeMinute(c), p.getString("nudgedOn", ""));
    }

    private static void arm(AlarmManager am, PendingIntent pi, int minuteOfDay, String doneOn) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        at.set(Calendar.MINUTE, minuteOfDay % 60);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis() || day(at).equals(doneOn)) at.add(Calendar.DAY_OF_YEAR, 1);
        // Inexact but allowed in Doze; a reminder a few minutes late is fine and needs no exact-alarm permission.
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
        else am.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
    }

    static void fire(Context c) {
        SharedPreferences p = prefs(c);
        String now = today();
        if (p.getBoolean("enabled", false) && !now.equals(p.getString("allDone", "")) && !now.equals(p.getString("firedOn", ""))) {
            notifyNow(c, c.getString(R.string.reminder_title), c.getString(R.string.reminder_text));
            p.edit().putBoolean("nudgeShown", false).apply();
        }
        p.edit().putString("firedOn", now).apply();
        schedule(c);
    }

    static void fireNudge(Context c) {
        SharedPreferences p = prefs(c);
        String now = today();
        if (!now.equals(p.getString("nudgedOn", "")) && nudgeDue(c)) {
            int streak = p.getInt("streak", 0);
            notifyNow(c, c.getString(R.string.streak_title, streak), c.getString(R.string.streak_text));
            p.edit().putBoolean("nudgeShown", true).apply();
        }
        p.edit().putString("nudgedOn", now).apply();
        schedule(c);
    }

    private static boolean nudgeDue(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("enabled", false) && p.getBoolean("nudge", true)
            && today().equals(p.getString("riskDay", ""))
            && p.getInt("streak", 0) >= MIN_STREAK && !p.getBoolean("covered", false);
    }

    private static List<String> plays(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        for (String s : p.getString("plays", "").split(",")) if (s.matches("\\d{4}-\\d\\d-\\d\\d@\\d+")) out.add(s);
        return out;
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    private static PendingIntent alarmIntent(Context c, String action, int code) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static void cancelNotification(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
    }

    @SuppressWarnings("deprecation")
    private static void notifyNow(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(c, nm);
            try {
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                return;
            }
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_stat_zap)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable()));
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFFFF8A1F);
        nm.notify(NOTIFICATION_ID, b.build());
    }

    // new NotificationChannel(CHANNEL, name, IMPORTANCE_DEFAULT) via reflection (API 26+).
    private static void ensureChannel(Context c, NotificationManager nm) {
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(CHANNEL, c.getString(R.string.reminder_channel), 3);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
        } catch (Exception ignored) {
        }
    }
}
