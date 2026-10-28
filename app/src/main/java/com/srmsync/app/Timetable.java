package com.srmsync.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the JSON from GET /api/timetable into a simple list of classes.
 * The rules copy the website's own "current class" logic in login.html,
 * so the app and the site always agree on what counts as a class.
 */
final class Timetable {

    /** One class: a weekday plus start/end in minutes from midnight. */
    static final class Block {
        final int day;      // Calendar.SUNDAY (1) .. Calendar.SATURDAY (7)
        final int start;
        final int end;
        final String label;
        final String room;

        Block(int day, int start, int end, String label, String room) {
            this.day = day;
            this.start = start;
            this.end = end;
            this.label = label;
            this.room = room;
        }
    }

    private static final String[] WEEKDAYS = {
        "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
    };

    private static final Pattern DAY_ORDER = Pattern.compile("^day\\s*[-_]?\\s*([1-6])");
    private static final Pattern RANGE =
        Pattern.compile("^\\s*(.*?)\\s*(?:-|\u2013|\u2014|to)\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME =
        Pattern.compile("^(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?$", Pattern.CASE_INSENSITIVE);

    private Timetable() {}

    static List<Block> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray schedule = root.optJSONArray("schedule");
        List<Block> out = new ArrayList<>();
        if (schedule == null) return out;

        Map<String, String> titles = new HashMap<>();
        JSONArray subjects = root.optJSONArray("subjects");
        if (subjects != null) {
            for (int i = 0; i < subjects.length(); i++) {
                JSONObject s = subjects.optJSONObject(i);
                if (s == null) continue;
                String code = first(s, "courseCode", "code");
                String title = first(s, "courseTitle", "title");
                if (!code.isEmpty() && !title.isEmpty()) {
                    titles.put(code.toLowerCase(Locale.ROOT), title);
                    titles.put(code.replaceAll("\\s+", "").toLowerCase(Locale.ROOT), title);
                }
            }
        }

        // If the timetable uses real weekday names, ignore any "Day N" rows,
        // just like the website does.
        boolean hasWeekdayRows = false;
        for (int i = 0; i < schedule.length(); i++) {
            JSONObject row = schedule.optJSONObject(i);
            if (row != null && weekday(row.optString("day", "")) != 0) hasWeekdayRows = true;
        }

        for (int i = 0; i < schedule.length(); i++) {
            JSONObject row = schedule.optJSONObject(i);
            if (row == null) continue;

            String dayText = row.optString("day", "");
            int day = weekday(dayText);
            if (day == 0 && !hasWeekdayRows) day = dayOrder(dayText);
            if (day == 0) continue;

            JSONArray slots = row.optJSONArray("slots");
            if (slots == null) continue;

            for (int j = 0; j < slots.length(); j++) {
                JSONObject slot = slots.optJSONObject(j);
                if (slot == null) continue;

                String code = first(slot, "courseCode", "code");
                String raw = slot.optString("codeRoom", "").trim();
                String clean = !code.isEmpty() ? code
                    : (!raw.equals("-") && !raw.equals("&nbsp;") ? raw : "");
                if (isFree(clean)) continue;

                int[] range = parseRange(first(slot, "time", "timing"));
                if (range == null) continue;

                String title = first(slot, "subjectName", "title");
                if (title.isEmpty()) {
                    String fromList = titles.get(clean.toLowerCase(Locale.ROOT));
                    if (fromList == null) fromList = titles.get(clean.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
                    title = fromList != null ? fromList : "";
                }
                String label = (title.isEmpty() || title.equalsIgnoreCase(clean)) ? clean : clean + " \u00b7 " + title;

                out.add(new Block(day, range[0], range[1], label, first(slot, "room", "roomNo")));
            }
        }

        Collections.sort(out, (a, b) -> a.day != b.day ? Integer.compare(a.day, b.day) : Integer.compare(a.start, b.start));
        return out;
    }

    // ---- saving and loading ----

    static String toJson(List<Block> blocks) {
        JSONArray arr = new JSONArray();
        try {
            for (Block b : blocks) {
                JSONObject o = new JSONObject();
                o.put("day", b.day);
                o.put("start", b.start);
                o.put("end", b.end);
                o.put("label", b.label);
                o.put("room", b.room);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            // put() only throws for NaN numbers, which we never have.
        }
        return arr.toString();
    }

    static List<Block> fromJson(String json) {
        List<Block> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Block(o.getInt("day"), o.getInt("start"), o.getInt("end"), o.optString("label", ""),
                    o.optString("room", "")));
            }
        } catch (JSONException ignored) {
            // A broken saved timetable behaves like no timetable.
        }
        return out;
    }

    static String hhmm(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ---- helpers ----

    private static int weekday(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < WEEKDAYS.length; i++) {
            if (t.startsWith(WEEKDAYS[i])) return Calendar.SUNDAY + i;
        }
        return 0;
    }

    /** "Day 1" is Monday, "Day 2" Tuesday ... the same mapping the website uses. */
    private static int dayOrder(String text) {
        Matcher m = DAY_ORDER.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.find()) return 0;
        return Calendar.SUNDAY + Integer.parseInt(m.group(1));
    }

    private static boolean isFree(String code) {
        if (code.isEmpty() || code.equals("-") || code.equals("&nbsp;")) return true;
        String u = code.toUpperCase(Locale.ROOT);
        return u.equals("FREE") || u.equals("NIL");
    }

    static int[] parseRange(String timing) {
        if (timing == null) return null;
        Matcher m = RANGE.matcher(timing);
        if (!m.matches()) return null;
        int start = parseTime(m.group(1));
        int end = parseTime(m.group(2));
        if (start < 0 || end < 0 || end <= start) return null;
        return new int[] { start, end };
    }

    /** "09:00", "9.00", "2:30 pm" -> minutes from midnight; -1 if unreadable. */
    static int parseTime(String text) {
        Matcher m = TIME.matcher(text.trim());
        if (!m.matches()) return -1;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        String ampm = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
        if (h > 23 || min > 59) return -1;
        if (ampm.equals("pm") && h < 12) h += 12;
        if (ampm.equals("am") && h == 12) h = 0;
        // SRM writes afternoon periods as 1:00, 2:00 ... without PM.
        if (ampm.isEmpty() && h >= 1 && h <= 6) h += 12;
        return h * 60 + min;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !v.equals("null")) return v;
        }
        return "";
    }
}
