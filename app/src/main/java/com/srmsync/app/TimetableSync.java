package com.srmsync.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the timetable from your own server (GET /api/timetable),
 * using the same login session the website inside the app is using.
 */
final class TimetableSync {

    static final String API = "https://srmsync.in/api/timetable";

    interface Callback {
        void done(boolean ok, boolean changed, String message);
    }

    private static final class Result {
        final boolean ok;
        final boolean changed;
        final String message;

        Result(boolean ok, boolean changed, String message) {
            this.ok = ok;
            this.changed = changed;
            this.message = message;
        }
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private TimetableSync() {}

    /** Sync when we see a new login, or the saved copy is older than 6 hours. */
    static boolean isDue(Prefs p, String sessionId, long now) {
        if (now - p.lastAttemptAt() < 2 * 60_000L) return false;   // don't hammer the server
        return !sessionId.equals(p.lastSyncSession()) || now - p.lastSyncAt() > 6 * 60 * 60_000L;
    }

    static void sync(Context context, String sessionId, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!running.compareAndSet(false, true)) {
            if (callback != null) main.post(() -> callback.done(false, false, "A sync is already running."));
            return;
        }

        Context app = context.getApplicationContext();
        Prefs p = new Prefs(app);
        p.setLastAttemptAt(System.currentTimeMillis());

        new Thread(() -> {
            Result result = fetch(app, p, sessionId);
            running.set(false);
            if (callback != null) {
                main.post(() -> callback.done(result.ok, result.changed, result.message));
            }
        }, "timetable-sync").start();
    }

    private static Result fetch(Context app, Prefs p, String sessionId) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(API).openConnection();
            con.setConnectTimeout(20_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("X-Client-Session", sessionId);
            con.setRequestProperty("Accept", "application/json");

            int code = con.getResponseCode();
            if (code == 401) {
                return new Result(false, false, "Login session expired. Log in again on the main screen, then sync.");
            }
            if (code != 200) {
                return new Result(false, false, "Server answered " + code + ". Try again later.");
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line).append('\n');
            }

            List<Timetable.Block> blocks = Timetable.parse(body.toString());
            if (blocks.isEmpty()) {
                return new Result(false, false, "The timetable came back empty, so the old one was kept.");
            }

            String json = Timetable.toJson(blocks);
            boolean changed = !json.equals(p.blocksJson());
            p.saveTimetable(json, sessionId, System.currentTimeMillis());
            SilentScheduler.refresh(app);
            return new Result(true, changed, "Timetable synced: " + blocks.size() + " classes a week.");
        } catch (Exception e) {
            return new Result(false, false, "Sync failed: " + e.getClass().getSimpleName() + ". Check your internet.");
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
