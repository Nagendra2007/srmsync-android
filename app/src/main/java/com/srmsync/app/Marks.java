package com.srmsync.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Internal marks from GET /api/internal-marks, saved on the phone for sharing. */
final class Marks {

    static final class Subject {
        final String code;
        final String name;
        final double obtained;   // NaN when the portal shows none yet
        final double max;
        final List<String[]> parts;   // {name, value}

        Subject(String code, String name, double obtained, double max, List<String[]> parts) {
            this.code = code;
            this.name = name;
            this.obtained = obtained;
            this.max = max;
            this.parts = parts;
        }
    }

    private Marks() {}

    static void handle(Context context, int status, String body) {
        if (status != 200) return;
        try {
            List<Subject> list = parse(body);
            if (list.isEmpty()) return;
            new Prefs(context).saveMarks(toJson(list), System.currentTimeMillis());
        } catch (JSONException ignored) {
            // keep the last good copy
        }
    }

    static List<Subject> parse(String body) throws JSONException {
        String t = body.trim();
        JSONArray arr = t.startsWith("[") ? new JSONArray(t)
            : new JSONObject(t).optJSONArray("subjects");
        List<Subject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String code = s.optString("code", "").trim();
            if (code.isEmpty()) continue;
            List<String[]> parts = new ArrayList<>();
            JSONArray comps = s.optJSONArray("components");
            if (comps != null) {
                for (int j = 0; j < comps.length(); j++) {
                    JSONObject c = comps.optJSONObject(j);
                    if (c == null) continue;
                    String name = c.optString("name", "").trim();
                    String value = text(c, "converted");
                    if (value.isEmpty()) value = text(c, "conducted");
                    if (!name.isEmpty() && !value.isEmpty()) parts.add(new String[] { name, value });
                }
            }
            out.add(new Subject(code, firstNonEmpty(s.optString("description", ""), s.optString("name", "")).trim(),
                number(s, "marksObtained"), number(s, "maxMarks"), parts));
        }
        return out;
    }

    static String toJson(List<Subject> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Subject s : list) {
                JSONArray parts = new JSONArray();
                for (String[] p : s.parts) parts.put(new JSONArray().put(p[0]).put(p[1]));
                JSONObject o = new JSONObject().put("code", s.code).put("name", s.name).put("parts", parts);
                if (!Double.isNaN(s.obtained)) o.put("obtained", s.obtained);
                if (!Double.isNaN(s.max)) o.put("max", s.max);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            // only plain values go in
        }
        return arr.toString();
    }

    static List<Subject> saved(Prefs p) {
        List<Subject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.marksJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<String[]> parts = new ArrayList<>();
                JSONArray pa = o.optJSONArray("parts");
                if (pa != null) {
                    for (int j = 0; j < pa.length(); j++) {
                        JSONArray one = pa.optJSONArray(j);
                        if (one != null) parts.add(new String[] { one.optString(0), one.optString(1) });
                    }
                }
                out.add(new Subject(o.optString("code"), o.optString("name"),
                    o.has("obtained") ? o.optDouble("obtained") : Double.NaN,
                    o.has("max") ? o.optDouble("max") : Double.NaN, parts));
            }
        } catch (JSONException ignored) {
            // nothing saved yet
        }
        return out;
    }

    /** 18.0 -> "18", 18.5 -> "18.5" */
    static String num(double v) {
        if (Double.isNaN(v)) return "\u2013";
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    private static double number(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return Double.NaN;
        double d = o.optDouble(key, Double.NaN);
        return Double.isInfinite(d) ? Double.NaN : d;
    }

    private static String text(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return "";
        String v = o.optString(key, "").trim();
        return v.equals("null") ? "" : v;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }
}
