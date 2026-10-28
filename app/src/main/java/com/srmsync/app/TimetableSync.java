package com.srmsync.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Handles the timetable that the website page fetched for us.
 *
 * The download itself happens inside the WebView (see MainActivity), using the
 * page's own fetch(), so it goes out exactly like the website's own requests.
 * This class only decides whether to sync, and turns the answer into saved classes.
 */
final class TimetableSync {

    static final class Result {
        final boolean ok;
        final boolean changed;
        final String message;

        Result(boolean ok, boolean changed, String message) {
            this.ok = ok;
            this.changed = changed;
            this.message = message;
        }
    }

    private TimetableSync() {}

    /** Sync when we see a new login, or the saved copy is older than 6 hours. */
    static boolean isDue(Prefs p, String sessionId, long now) {
        if (now - p.lastAttemptAt() < 2 * 60_000L) return false;   // don't hammer the server
        return !sessionId.equals(p.lastSyncSession()) || now - p.lastSyncAt() > 6 * 60 * 60_000L;
    }

    static Result handle(Context context, String sessionId, int status, String body) {
        Context app = context.getApplicationContext();
        Prefs p = new Prefs(app);
        Result r = process(app, p, sessionId, status, body);
        p.setLastSyncMessage(SilentScheduler.clock(System.currentTimeMillis(), "d MMM, HH:mm") + " \u2013 " + r.message);
        return r;
    }

    private static Result process(Context app, Prefs p, String sessionId, int status, String body) {
        if (status == 0) {
            return new Result(false, false, "Couldn't reach the server (" + shorten(body) + ").");
        }
        if (status == 401) {
            return new Result(false, false, "Login session expired. Log out and log in again, then sync.");
        }
        if (status != 200) {
            return new Result(false, false, "Server answered " + status + ": " + shorten(body));
        }
        try {
            List<Timetable.Block> blocks = Timetable.parse(body);
            if (blocks.isEmpty()) {
                return new Result(false, false, "Got the timetable but found no classes in it. " + hint(body));
            }
            String json = Timetable.toJson(blocks);
            boolean changed = !json.equals(p.blocksJson());
            p.saveTimetable(json, sessionId, System.currentTimeMillis());
            SilentScheduler.refresh(app);
            return new Result(true, changed, "Timetable synced: " + blocks.size() + " classes a week.");
        } catch (Exception e) {
            return new Result(false, false, "Couldn't read the timetable: " + shorten(body));
        }
    }

    /** A short clue for empty results, so we can see what the server actually sent. */
    private static String hint(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray schedule = root.optJSONArray("schedule");
            int days = schedule == null ? 0 : schedule.length();
            String day = "";
            String time = "";
            if (days > 0) {
                JSONObject row = schedule.optJSONObject(0);
                day = row == null ? "" : row.optString("day", "");
                JSONArray slots = row == null ? null : row.optJSONArray("slots");
                JSONObject slot = slots == null ? null : slots.optJSONObject(0);
                time = slot == null ? "" : slot.optString("time", slot.optString("timing", ""));
            }
            return "(days: " + days + ", first day: \"" + day + "\", first time: \"" + time + "\")";
        } catch (Exception e) {
            return "";
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "\u2026" : s;
    }
}
