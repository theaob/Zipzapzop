package com.onurbaykal.zipzapzop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Fires the daily reminder, and re-arms it after a reboot, clock or timezone change, or app update.
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Reminder.ACTION_FIRE.equals(action)) Reminder.fire(context);
        else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_TIME_CHANGED.equals(action)
            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) Reminder.schedule(context);
    }
}
