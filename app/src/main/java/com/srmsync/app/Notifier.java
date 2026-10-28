package com.srmsync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** All of the app's notifications in one place. */
final class Notifier {

    static final String CH_SILENT = "class_silent";
    static final String CH_REMIND = "class_reminders";
    static final String CH_ATTEND = "attendance";

    private static final int ID_SILENT = 100;
    private static final int ID_ATTEND = 300;
    // Reminders use 200 + minute-of-week so two reminders never overwrite each other.

    static final String ACTION_END_EARLY = "com.srmsync.app.END_EARLY";

    private Notifier() {}

    static boolean allowed(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    private static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel silent = new NotificationChannel(CH_SILENT, "Silent during class",
            NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("Shows while your phone is silenced for a class");
        silent.setShowBadge(false);

        NotificationChannel remind = new NotificationChannel(CH_REMIND, "Class reminders",
            NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("A heads-up before each class, with the room");

        NotificationChannel attend = new NotificationChannel(CH_ATTEND, "Attendance warnings",
            NotificationManager.IMPORTANCE_DEFAULT);
        attend.setDescription("When a subject gets close to 75%");

        nm.createNotificationChannel(silent);
        nm.createNotificationChannel(remind);
        nm.createNotificationChannel(attend);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel) {
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(c, channel)
            : new Notification.Builder(c);
        int icon = c.getResources().getIdentifier("ic_stat_bell", "drawable", c.getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_lock_silent_mode);
        b.setColor(Ui.RED);
        return b;
    }

    private static PendingIntent open(Context c, Class<?> screen, int code) {
        Intent i = new Intent(c, screen);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void post(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // notification permission not granted
        }
    }

    // ------------------------------------------------------------------ "Silent until 10:50"

    static void showSilent(Context c, long until, String label, boolean test) {
        Intent end = new Intent(c, ActionReceiver.class).setAction(ACTION_END_EARLY);
        PendingIntent endPi = PendingIntent.getBroadcast(c, 101, end,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = builder(c, CH_SILENT)
            .setContentTitle("Silent until " + SilentScheduler.clock(until, "HH:mm"))
            .setContentText(test ? "Test run. Switches back by itself." : label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open(c, SettingsActivity.class, 102))
            .addAction(new Notification.Action.Builder(null, "End early", endPi).build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        post(c, ID_SILENT, b.build());
    }

    static void clearSilent(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_SILENT);
    }

    // ------------------------------------------------------------------ class reminder

    static void showReminder(Context c, Timetable.Block b, long startMillis, int minutesBefore) {
        String title = b.label + " at " + Timetable.hhmm(b.start);
        String text = (b.room.isEmpty() ? "Starts" : "Room " + b.room + ", starts")
            + " in " + minutesBefore + " minutes";
        Notification.Builder n = builder(c, CH_REMIND)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setWhen(startMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setTimeoutAfter(Math.max(60_000L, startMillis + 15 * 60_000L - System.currentTimeMillis()))
            .setContentIntent(open(c, SettingsActivity.class, 201))
            .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) n.setPriority(Notification.PRIORITY_HIGH);
        post(c, 200 + b.day * 1440 + b.start, n.build());
    }

    // ------------------------------------------------------------------ attendance

    static void showAttendance(Context c, String title, String text) {
        Notification.Builder n = builder(c, CH_ATTEND)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(c, SettingsActivity.class, 301));
        post(c, ID_ATTEND, n.build());
    }
}
