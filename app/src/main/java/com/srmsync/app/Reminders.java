package com.srmsync.app;

import android.content.Context;

import java.util.List;

/** "DE in CB-301 at 10:00" a few minutes before each class. */
final class Reminders {

    /** Don't show a reminder that is more than this late (e.g. phone was off). */
    private static final long STALE_MS = 5 * 60_000L;

    private Reminders() {}

    private static List<SilentScheduler.Occurrence> classes(Prefs p, long now) {
        return SilentScheduler.occurrences(Timetable.fromJson(p.blocksJson()), p.skipDate(), now);
    }

    /** When the next reminder is due, or -1. */
    static long nextTime(Prefs p, long now) {
        if (!p.remindersOn()) return -1;
        long before = p.reminderMinutes() * 60_000L;
        for (SilentScheduler.Occurrence o : classes(p, now)) {
            long at = o.start - before;
            if (at > now) return at;
        }
        return -1;
    }

    /** Shows any reminder whose time has just arrived. */
    static void fireDue(Context c, Prefs p, long now) {
        if (!p.remindersOn()) return;
        long before = p.reminderMinutes() * 60_000L;
        long lastFired = p.lastReminderAt();
        long newest = lastFired;

        // Look back a little so today's earlier classes are included in the list.
        for (SilentScheduler.Occurrence o : classes(p, now - STALE_MS - before)) {
            long at = o.start - before;
            if (at <= lastFired || at > now) continue;
            if (now - at <= STALE_MS) {
                Notifier.showReminder(c, o.block, o.start, p.reminderMinutes());
            }
            newest = Math.max(newest, at);
        }
        if (newest != lastFired) p.setLastReminderAt(newest);
    }
}
