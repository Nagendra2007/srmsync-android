package com.srmsync.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The heart of the auto-silent feature.
 *
 * Instead of setting dozens of alarms, it works like this every time it runs:
 *   1. Look at the clock and the timetable: "should the phone be silent right now?"
 *   2. Make the ringer match that answer.
 *   3. Set ONE alarm for the next moment the answer changes (a class starting or ending).
 * When that alarm fires, AlarmReceiver calls refresh() again, and the chain continues.
 * Because it always checks "right now", a late alarm or a restart mid-class still ends up correct.
 */
final class SilentScheduler {

    /** Breaks this short (or shorter) stay silent when "short breaks" is on. */
    static final long SHORT_BREAK_MS = 10 * 60_000L;

    static final class Interval {
        final long start;
        final long end;
        final String label;

        Interval(long start, long end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    private SilentScheduler() {}

    // ------------------------------------------------------------------ main entry

    static synchronized void refresh(Context context) {
        Context app = context.getApplicationContext();
        Prefs p = new Prefs(app);
        long now = System.currentTimeMillis();

        List<Interval> classes = p.enabled() ? upcoming(p, now) : Collections.emptyList();
        boolean inClass = current(classes, now) != null;
        boolean testing = now < p.testUntil();

        applyRinger(app, p, inClass || testing);
        scheduleAlarm(app, nextChange(classes, now, p.testUntil()));
    }

    // ------------------------------------------------------------------ timetable -> real times

    /** All class periods from today through the next 7 days, as real clock times. */
    static List<Interval> upcoming(Prefs p, long now) {
        return upcoming(Timetable.fromJson(p.blocksJson()), p.mergeBreaks(), p.skipDate(), now);
    }

    static List<Interval> upcoming(List<Timetable.Block> blocks, boolean mergeBreaks, String skip, long now) {
        List<Interval> out = new ArrayList<>();
        if (blocks.isEmpty()) return out;

        long gapAllowed = mergeBreaks ? SHORT_BREAK_MS : 0;

        for (int d = 0; d < 8; d++) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(now);
            day.add(Calendar.DAY_OF_YEAR, d);
            if (dateKey(day.getTimeInMillis()).equals(skip)) continue;

            int weekday = day.get(Calendar.DAY_OF_WEEK);
            Interval last = null;

            for (Timetable.Block b : blocks) {   // already sorted by day, then start
                if (b.day != weekday) continue;
                long start = at(day, b.start);
                long end = at(day, b.end);

                if (last != null && start - last.end <= gapAllowed) {
                    String label = last.label.contains(b.label) ? last.label : last.label + ", " + b.label;
                    last = new Interval(last.start, Math.max(last.end, end), label);
                    out.set(out.size() - 1, last);
                } else {
                    last = new Interval(start, end, b.label);
                    out.add(last);
                }
            }
        }
        return out;
    }

    static Interval current(List<Interval> list, long now) {
        for (Interval i : list) {
            if (i.start <= now && now < i.end) return i;
        }
        return null;
    }

    static Interval nextClass(List<Interval> list, long now) {
        for (Interval i : list) {
            if (i.start > now) return i;
        }
        return null;
    }

    /** The next moment "silent or not" can flip. -1 if there is none. */
    static long nextChange(List<Interval> list, long now, long testUntil) {
        long best = Long.MAX_VALUE;
        for (Interval i : list) {
            if (i.start > now) best = Math.min(best, i.start);
            if (i.end > now) best = Math.min(best, i.end);
        }
        if (testUntil > now) best = Math.min(best, testUntil);
        return best == Long.MAX_VALUE ? -1 : best;
    }

    // ------------------------------------------------------------------ the ringer

    private static void applyRinger(Context c, Prefs p, boolean shouldBeSilent) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) return;
        int currentMode = audio.getRingerMode();

        if (shouldBeSilent) {
            if (p.appSilenced()) return;   // already done for this class
            int target = p.vibrateMode() ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
            if (currentMode == target) {
                // Student already had it this way. Remember that, so we don't "restore" anything later.
                p.markSilenced(currentMode, currentMode);
                return;
            }
            try {
                audio.setRingerMode(target);
                p.markSilenced(currentMode, target);
            } catch (SecurityException e) {
                // "Do Not Disturb access" not granted yet. The settings screen shows this.
            }
        } else {
            if (!p.appSilenced()) return;
            // Only restore if the student didn't change it themselves during class.
            if (currentMode == p.setRinger() && p.prevRinger() >= 0 && p.prevRinger() != currentMode) {
                try {
                    audio.setRingerMode(p.prevRinger());
                } catch (SecurityException e) {
                    return;   // keep the flag so we try again next time
                }
            }
            p.clearSilenced();
        }
    }

    // ------------------------------------------------------------------ the alarm

    private static PendingIntent alarmIntent(Context c) {
        Intent intent = new Intent(c, AlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleAlarm(Context c, long at) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent pi = alarmIntent(c);
        alarms.cancel(pi);
        if (at <= 0) return;

        long when = at + 1000;   // one second after the bell, so "now" is safely past it
        try {
            if (canUseExactAlarms(c)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    // ------------------------------------------------------------------ permission checks

    static boolean hasDndAccess(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    static boolean canUseExactAlarms(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    // ------------------------------------------------------------------ text for the settings screen

    static String describe(Context c) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();

        if (now < p.testUntil()) {
            sb.append("Test running: silent until ").append(clock(p.testUntil(), "HH:mm:ss")).append(".\n\n");
        }
        if (Timetable.fromJson(p.blocksJson()).isEmpty()) {
            return sb.append("No timetable yet. Go back, log in to SRMSync once, and it syncs by itself.").toString();
        }
        if (!p.enabled()) {
            return sb.append("Auto-silent is switched off.").toString();
        }
        if (p.skipDate().equals(dateKey(now))) {
            sb.append("Skipping today. Back to normal tomorrow.\n\n");
        }

        List<Interval> list = upcoming(p, now);
        Interval cur = current(list, now);
        if (cur != null) {
            sb.append("In class now: ").append(cur.label)
              .append("\nPhone stays silent until ").append(clock(cur.end, "HH:mm")).append(".");
        } else {
            Interval next = nextClass(list, now);
            if (next == null) {
                sb.append("No classes in the next 7 days.");
            } else {
                sb.append("Next silent period: ").append(clock(next.start, "EEE HH:mm"))
                  .append(" \u2013 ").append(clock(next.end, "HH:mm"))
                  .append("\n").append(next.label);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ small helpers

    private static long at(Calendar day, int minutes) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String dateKey(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    static String clock(long millis, String pattern) {
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
    }
}
