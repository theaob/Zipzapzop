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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

// Daily challenge reminder: a local notification at a time the player picks, scheduled with
// AlarmManager and re-armed after each firing, reboot, time change or app update. Nothing leaves
// the device. Built against the API 23 SDK, so newer calls (notification channels) use reflection.
final class Reminder {
    static final String ACTION_FIRE = "com.onurbaykal.zipzapzop.REMINDER";
    private static final String PREFS = "reminder";
    private static final String CHANNEL = "daily";
    private static final int NOTIFICATION_ID = 1;

    private Reminder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static void configure(Context c, boolean enabled, int hour, int minute) {
        prefs(c).edit().putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute).apply();
        schedule(c);
    }

    // The page reports when all of a day's dailies are solved, so that day's reminder is skipped.
    static void markAllDone(Context c, String date) {
        prefs(c).edit().putString("allDone", date).apply();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("enabled", false)) return;
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, p.getInt("hour", 9));
        at.set(Calendar.MINUTE, p.getInt("minute", 0));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        // Inexact but allowed in Doze; a reminder a few minutes late is fine and needs no exact-alarm permission.
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
        else am.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
    }

    static void fire(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("enabled", false) && !today().equals(p.getString("allDone", ""))) notifyNow(c);
        schedule(c);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    @SuppressWarnings("deprecation")
    private static void notifyNow(Context c) {
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
            .setContentTitle(c.getString(R.string.reminder_title))
            .setContentText(c.getString(R.string.reminder_text))
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
