package com.srmsync.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads attendance from GET /api/attendance (fetched by the website page, like the timetable)
 * and warns when a subject is close to, or below, 75%.
 */
final class AttendanceWatch {

    static final double MIN = 75.0;

    static final int OK = 0;
    static final int EDGE = 1;   // 75% or more, but can miss at most 1 more
    static final int LOW = 2;    // below 75%

    static final class Subject {
        final String code;
        final String name;
        final int present;
        final int total;
        final double percent;
        final int canMiss;   // when at or above 75%
        final int needed;    // classes in a row to get back to 75%
        final int state;

        Subject(String code, String name, int present, int total) {
            this.code = code;
            this.name = name;
            this.present = present;
            this.total = total;
            this.percent = total == 0 ? 100.0 : present * 100.0 / total;
            if (percent < MIN) {
                canMiss = 0;
                needed = (int) Math.ceil((0.75 * total - present) / 0.25);
                state = LOW;
            } else {
                canMiss = Math.max(0, (int) Math.floor(present / 0.75 - total));
                needed = 0;
                state = canMiss <= 1 ? EDGE : OK;
            }
        }

        int absent() { return total - present; }

        String percentText() { return String.format(Locale.US, "%.1f%%", percent); }

        /** The one-line advice shown in the app and in notifications. */
        String advice() {
            if (state == LOW) {
                return "Attend the next " + needed + (needed == 1 ? " class" : " classes") + " to get back to 75%.";
            }
            if (canMiss == 0) return "You can't miss any more classes.";
            if (canMiss == 1) return "You can miss only 1 more class.";
            return "You can miss " + canMiss + " more classes.";
        }

        String shortName() {
            return name.isEmpty() ? code : name;
        }
    }

    private AttendanceWatch() {}

    // ------------------------------------------------------------------ incoming data

    static void handle(Context context, int status, String body) {
        if (status != 200) return;   // timeouts etc. are handled by the website itself
        Context app = context.getApplicationContext();
        Prefs p = new Prefs(app);

        List<Subject> subjects;
        try {
            subjects = parse(body);
        } catch (JSONException e) {
            return;
        }
        if (subjects.isEmpty()) return;

        p.saveAttendance(toJson(subjects), System.currentTimeMillis());
        warnIfNeeded(app, p, subjects);
    }

    static List<Subject> parse(String body) throws JSONException {
        String t = body.trim();
        JSONArray arr;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            JSONObject o = new JSONObject(t);
            arr = o.optJSONArray("subjects");
            if (arr == null) arr = o.optJSONArray("attendance");
            if (arr == null) arr = new JSONArray();
        }
        List<Subject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String code = s.optString("code", "").trim();
            int present = s.optInt("present", -1);
            int absent = s.optInt("absent", -1);
            if (code.isEmpty() || present < 0 || absent < 0 || present + absent == 0) continue;
            out.add(new Subject(code, s.optString("description", "").trim(), present, present + absent));
        }
        return out;
    }

    // ------------------------------------------------------------------ saved copy, for the settings screen

    static String toJson(List<Subject> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Subject s : list) {
                arr.put(new JSONObject()
                    .put("code", s.code).put("name", s.name)
                    .put("present", s.present).put("total", s.total));
            }
        } catch (JSONException ignored) {
            // never happens for plain strings and ints
        }
        return arr.toString();
    }

    static List<Subject> saved(Prefs p) {
        List<Subject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.attendanceJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Subject(o.optString("code"), o.optString("name"), o.optInt("present"), o.optInt("total")));
            }
        } catch (JSONException ignored) {
            // nothing saved yet
        }
        return out;
    }

    // ------------------------------------------------------------------ warnings

    /**
     * Warn when a subject gets worse (fine -> close -> below 75%), or when you miss
     * another class while already close or below. Same numbers = no repeat warning.
     */
    private static void warnIfNeeded(Context c, Prefs p, List<Subject> subjects) {
        Map<String, int[]> before = readWarned(p.attendanceWarned());   // code -> {state, absent}
        List<Subject> worse = new ArrayList<>();

        StringBuilder now = new StringBuilder();
        for (Subject s : subjects) {
            int[] prev = before.get(s.code);
            boolean gotWorse = prev == null ? s.state != OK
                : s.state > prev[0] || (s.state != OK && s.absent() > prev[1]);
            if (gotWorse) worse.add(s);
            now.append(s.code.replace(";", "").replace("=", "")).append('=')
               .append(s.state).append(':').append(s.absent()).append(';');
        }
        p.setAttendanceWarned(now.toString());

        if (worse.isEmpty() || !p.attendanceAlertsOn()) return;

        if (worse.size() == 1) {
            Subject s = worse.get(0);
            Notifier.showAttendance(c, s.shortName() + ": " + s.percentText() + " attendance", s.advice());
        } else {
            StringBuilder text = new StringBuilder();
            for (Subject s : worse) {
                if (text.length() > 0) text.append('\n');
                text.append(s.shortName()).append(": ").append(s.percentText()).append(". ").append(s.advice());
            }
            Notifier.showAttendance(c, worse.size() + " subjects need attention", text.toString());
        }
    }

    private static Map<String, int[]> readWarned(String saved) {
        Map<String, int[]> out = new HashMap<>();
        for (String part : saved.split(";")) {
            int eq = part.lastIndexOf('=');
            int colon = part.lastIndexOf(':');
            if (eq <= 0 || colon <= eq) continue;
            try {
                out.put(part.substring(0, eq), new int[] {
                    Integer.parseInt(part.substring(eq + 1, colon)),
                    Integer.parseInt(part.substring(colon + 1)) });
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
        }
        return out;
    }
}
