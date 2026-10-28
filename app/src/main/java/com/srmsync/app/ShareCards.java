package com.srmsync.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the share images: a navy header like the app's status board,
 * then white cards with one row per class or subject. Always light,
 * so they look the same in whatever chat they land in.
 */
final class ShareCards {

    private static final int W = 1080;
    private static final int PAD = 60;
    private static final int CARD_X = PAD;
    private static final int CARD_W = W - 2 * PAD;
    private static final int IN = 40;              // padding inside a card
    private static final int LEFT_COL = 190;       // the big number / time column

    private static final int NAVY = Color.parseColor("#0F1B2D");
    private static final int BG = Color.parseColor("#F2F4F8");
    private static final int CARD = Color.WHITE;
    private static final int LINE = Color.parseColor("#E1E6EE");
    private static final int TEXT = Color.parseColor("#13203A");
    private static final int MUTED = Color.parseColor("#5E6B82");
    private static final int ON_NAVY = Color.parseColor("#F4F7FC");
    private static final int ON_NAVY_MUTED = Color.parseColor("#9FB0CB");
    private static final int AMBER = Color.parseColor("#C77700");
    private static final int BLUE = Color.parseColor("#2F6FDB");
    private static final int TRACK = Color.parseColor("#E9EDF3");

    private static final String[] DAYS = { "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
    private static final int[] WEEK = { Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY };

    private ShareCards() {}

    // ================================================================== what goes on each image

    static File timetable(Context c, List<Timetable.Block> all, boolean todayOnly) throws Exception {
        Sheet s = new Sheet(c);
        Calendar now = Calendar.getInstance();
        if (todayOnly) {
            int today = now.get(Calendar.DAY_OF_WEEK);
            List<Timetable.Block> list = forDay(all, today);
            s.header("Today's classes", SilentScheduler.clock(now.getTimeInMillis(), "EEEE, d MMMM")
                + "  \u2013  " + plural(list.size(), "class", "classes"));
            if (list.isEmpty()) s.note("No classes today.");
            else s.card(classRows(list));
        } else {
            return weekTable(c, all);
        }
        s.footer("Shared from Srm Sync");
        return s.save("timetable");
    }

    // ------------------------------------------------------------------ the week as a grid

    private static final int[][] TINTS = {   // {fill, swatch}
        { Color.parseColor("#FDE2E3"), Color.parseColor("#E3262B") },
        { Color.parseColor("#DCE8FD"), Color.parseColor("#2F6FDB") },
        { Color.parseColor("#FDEBD0"), Color.parseColor("#D98B00") },
        { Color.parseColor("#DDF3E6"), Color.parseColor("#1E9E6A") },
        { Color.parseColor("#ECE3FB"), Color.parseColor("#7A4FD6") },
        { Color.parseColor("#D9F1F3"), Color.parseColor("#14929C") },
        { Color.parseColor("#FBE1EF"), Color.parseColor("#C93B87") },
        { Color.parseColor("#E6EAF0"), Color.parseColor("#5E6B82") },
    };

    /**
     * Days down the side, periods across the top, one coloured cell per class
     * (a lab that runs two periods becomes one wide cell), and a key below
     * that turns codes back into subject names.
     */
    static File weekTable(Context c, List<Timetable.Block> all) throws Exception {
        Ui ui = Ui.get(c);

        // Columns: every distinct period time. Rows: days that have classes.
        List<int[]> slots = new ArrayList<>();
        for (Timetable.Block b : all) {
            boolean seen = false;
            for (int[] s : slots) if (s[0] == b.start && s[1] == b.end) seen = true;
            if (!seen) slots.add(new int[] { b.start, b.end });
        }
        Collections.sort(slots, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<Integer> days = new ArrayList<>();
        for (int d : WEEK) if (!forDay(all, d).isEmpty()) days.add(d);

        // Subjects in code order, each with its own colour.
        List<String> codes = new ArrayList<>();
        Map<String, String> titles = new HashMap<>();
        for (Timetable.Block b : all) {
            String code = codeOf(b.label);
            if (!codes.contains(code)) codes.add(code);
            String t = titleOf(b.label);
            if (!t.isEmpty()) titles.put(code, t);
        }
        Collections.sort(codes);

        final int dayW = 170;
        final int colW = 176;
        final int headH = 104;
        final int rowH = 146;
        int tableW = dayW + slots.size() * colW;
        int width = Math.max(W, tableW + 2 * PAD);
        int tableX = (width - tableW) / 2;
        int tableH = headH + days.size() * rowH;

        int keyCols = Math.max(1, (width - 2 * PAD) / 500);
        int keyColW = (width - 2 * PAD) / keyCols;
        int keyRows = (codes.size() + keyCols - 1) / keyCols;
        int keyH = 118 + keyRows * 70 + 30;

        int headerTop = 60;
        int tableTop = headerTop + 410;
        int keyTop = tableTop + tableH + 30;
        int height = keyTop + keyH + 130;

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(BG);
        drawHeader(cv, ui, loadIcon(c), width, headerTop, "My timetable",
            plural(all.size(), "class", "classes") + " a week");

        // Table frame
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(LINE);
        line.setStrokeWidth(2);
        RectF frame = new RectF(tableX, tableTop, tableX + tableW, tableTop + tableH);
        fill.setColor(CARD);
        cv.drawRoundRect(frame, 36, 36, fill);
        cv.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(frame, 36, 36, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        fill.setColor(Color.parseColor("#E9EEF6"));
        cv.drawRect(tableX, tableTop, tableX + tableW, tableTop + headH, fill);
        cv.restore();

        // Period times across the top
        Paint timeP = paint(ui.boardSemi, 38, TEXT);
        timeP.setTextAlign(Paint.Align.CENTER);
        Paint endP = paint(ui.regular, 26, MUTED);
        endP.setTextAlign(Paint.Align.CENTER);
        for (int j = 0; j < slots.size(); j++) {
            float cx = tableX + dayW + j * colW + colW / 2f;
            cv.drawText(Timetable.hhmm(slots.get(j)[0]), cx, tableTop + 52, timeP);
            cv.drawText("to " + Timetable.hhmm(slots.get(j)[1]), cx, tableTop + 86, endP);
        }

        // Grid lines
        for (int i = 0; i <= days.size(); i++) {
            float y = tableTop + headH + i * rowH;
            if (i < days.size()) cv.drawLine(tableX, y, tableX + tableW, y, line);
        }
        for (int j = 0; j <= slots.size(); j++) {
            float x = tableX + dayW + j * colW;
            if (j < slots.size()) cv.drawLine(x, tableTop, x, tableTop + tableH, line);
        }

        // Day names and cells
        Paint dayP = paint(ui.semibold, 36, TEXT);
        Paint codeP = paint(ui.boardSemi, 40, TEXT);
        codeP.setTextAlign(Paint.Align.CENTER);
        Paint roomP = paint(ui.regular, 26, MUTED);
        roomP.setTextAlign(Paint.Align.CENTER);
        Paint dashP = paint(ui.regular, 30, LINE);
        dashP.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < days.size(); i++) {
            int day = days.get(i);
            float rowTop = tableTop + headH + i * rowH;
            cv.drawText(DAYS[day].substring(0, 3), tableX + 34, rowTop + rowH / 2f + 13, dayP);

            List<Timetable.Block> list = forDay(all, day);
            int j = 0;
            while (j < slots.size()) {
                Timetable.Block b = find(list, slots.get(j));
                float cellX = tableX + dayW + j * colW;
                if (b == null) {
                    cv.drawText("\u2013", cellX + colW / 2f, rowTop + rowH / 2f + 10, dashP);
                    j++;
                    continue;
                }
                int span = 1;   // a lab over two periods: one wide cell
                while (j + span < slots.size()) {
                    Timetable.Block nb = find(list, slots.get(j + span));
                    if (nb == null || !nb.label.equals(b.label) || !nb.room.equals(b.room)) break;
                    span++;
                }
                String code = codeOf(b.label);
                int tint = codes.indexOf(code) % TINTS.length;
                RectF cell = new RectF(cellX + 8, rowTop + 8, cellX + span * colW - 8, rowTop + rowH - 8);
                fill.setColor(TINTS[tint][0]);
                cv.drawRoundRect(cell, 20, 20, fill);
                float cx = cell.centerX();
                float maxText = cell.width() - 20;
                codeP.setTextSize(40);
                while (codeP.measureText(code) > maxText && codeP.getTextSize() > 24) {
                    codeP.setTextSize(codeP.getTextSize() - 2);
                }
                boolean hasRoom = !b.room.isEmpty();
                cv.drawText(code, cx, rowTop + rowH / 2f + (hasRoom ? -2 : 14), codeP);
                if (hasRoom) {
                    cv.drawText(TextUtils.ellipsize(b.room, new TextPaint(roomP), maxText, TextUtils.TruncateAt.END)
                        .toString(), cx, rowTop + rowH / 2f + 36, roomP);
                }
                j += span;
            }
        }

        // Border on top of everything
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2);
        border.setColor(LINE);
        cv.drawRoundRect(frame, 36, 36, border);

        // Key: code -> subject name
        Paint keyHead = paint(ui.semibold, 40, TEXT);
        cv.drawText("Subjects", PAD + 6, keyTop + 70, keyHead);
        TextPaint keyCode = textPaint(ui.semibold, 32, TEXT);
        TextPaint keyName = textPaint(ui.regular, 30, MUTED);
        for (int k = 0; k < codes.size(); k++) {
            String code = codes.get(k);
            int col = k % keyCols;
            int row = k / keyCols;
            float x = PAD + col * keyColW;
            float y = keyTop + 118 + row * 70;
            fill.setColor(TINTS[k % TINTS.length][1]);
            cv.drawRoundRect(new RectF(x + 6, y + 6, x + 34, y + 34), 8, 8, fill);
            cv.drawText(code, x + 52, y + 32, keyCode);
            float nameX = x + 52 + keyCode.measureText(code) + 18;
            String name = titles.containsKey(code) ? titles.get(code) : "";
            CharSequence fit = TextUtils.ellipsize(name, keyName, x + keyColW - 24 - nameX, TextUtils.TruncateAt.END);
            cv.drawText(fit.toString(), nameX, y + 32, keyName);
        }

        Paint foot = paint(ui.regular, 30, MUTED);
        foot.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Shared from Srm Sync", width / 2f, height - 60, foot);

        return saveBitmap(c, bmp, "timetable-week");
    }

    private static Timetable.Block find(List<Timetable.Block> list, int[] slot) {
        for (Timetable.Block b : list) if (b.start == slot[0] && b.end == slot[1]) return b;
        return null;
    }

    /** "CSE 207 \u00b7 Digital Electronics" -> "CSE 207" */
    private static String codeOf(String label) {
        int i = label.indexOf(" \u00b7 ");
        return i < 0 ? label : label.substring(0, i);
    }

    private static String titleOf(String label) {
        int i = label.indexOf(" \u00b7 ");
        return i < 0 ? "" : label.substring(i + 3);
    }

    static File attendance(Context c, List<AttendanceWatch.Subject> subjects, long at) throws Exception {
        Sheet s = new Sheet(c);
        s.header("My attendance", "Updated " + SilentScheduler.clock(at, "d MMM, HH:mm"));
        List<Row> rows = new ArrayList<>();
        for (AttendanceWatch.Subject a : subjects) {
            Row r = new Row();
            r.big = Math.round(Math.floor(a.percent)) + "%";
            r.bigColor = a.state == AttendanceWatch.LOW ? Ui.RED : a.state == AttendanceWatch.EDGE ? AMBER : TEXT;
            r.small = a.present + " of " + a.total;
            r.main = a.name.isEmpty() ? a.code : a.name;
            r.sub = a.code + "   " + (a.state == AttendanceWatch.LOW
                ? "Attend the next " + plural(a.needed, "class", "classes") + " to reach 75%"
                : a.canMiss == 0 ? "Can't miss any more" : "Can miss " + plural(a.canMiss, "more class", "more classes"));
            r.subColor = a.state == AttendanceWatch.LOW ? Ui.RED : a.state == AttendanceWatch.EDGE ? AMBER : MUTED;
            r.bar = (float) Math.min(1.0, a.percent / 100.0);
            r.barColor = a.state == AttendanceWatch.LOW ? Ui.RED : a.state == AttendanceWatch.EDGE ? AMBER : BLUE;
            r.marker75 = true;
            rows.add(r);
        }
        s.card(rows);
        s.footer("75% line marked on each bar. Shared from Srm Sync");
        return s.save("attendance");
    }

    static File marks(Context c, List<Marks.Subject> subjects, long at) throws Exception {
        Sheet s = new Sheet(c);
        s.header("Internal marks", "Updated " + SilentScheduler.clock(at, "d MMM, HH:mm"));
        List<Row> rows = new ArrayList<>();
        for (Marks.Subject m : subjects) {
            Row r = new Row();
            r.big = Marks.num(m.obtained);
            r.small = Double.isNaN(m.max) ? "" : "out of " + Marks.num(m.max);
            r.main = m.name.isEmpty() ? m.code : m.name;
            StringBuilder parts = new StringBuilder(m.code);
            for (String[] p : m.parts) parts.append("\n").append(p[0]).append(":  ").append(p[1]);
            r.sub = parts.toString();
            if (!Double.isNaN(m.obtained) && !Double.isNaN(m.max) && m.max > 0) {
                r.bar = (float) Math.max(0, Math.min(1, m.obtained / m.max));
                r.barColor = BLUE;
            }
            rows.add(r);
        }
        s.card(rows);
        s.footer("Shared from Srm Sync");
        return s.save("marks");
    }

    private static List<Row> classRows(List<Timetable.Block> list) {
        List<Row> rows = new ArrayList<>();
        for (Timetable.Block b : list) {
            Row r = new Row();
            r.big = Timetable.hhmm(b.start);
            r.small = "to " + Timetable.hhmm(b.end);
            r.main = b.label;
            r.sub = b.room.isEmpty() ? null : "Room " + b.room;
            rows.add(r);
        }
        return rows;
    }

    // ================================================================== drawing

    /** One line in a card. Anything left null is simply not drawn. */
    private static final class Row {
        String big;
        int bigColor = TEXT;
        String small;
        String main;
        String sub;
        int subColor = MUTED;
        float bar = -1f;
        int barColor = BLUE;
        boolean marker75;
    }

    private interface Block {
        int height();
        void draw(Canvas canvas, int top);
    }

    private static final class Sheet {
        private final Context c;
        private final Ui ui;
        private final List<Block> blocks = new ArrayList<>();

        Sheet(Context c) {
            this.c = c;
            this.ui = Ui.get(c);
        }

        void header(String title, String subtitle) {
            Bitmap icon = loadIcon(c);
            blocks.add(new Block() {
                public int height() { return 410; }   // 360 of navy + a 50 px gap before the first card
                public void draw(Canvas cv, int top) { drawHeader(cv, ui, icon, W, top, title, subtitle); }
            });
        }

        void section(String title) {
            Paint p = paint(ui.semibold, 44, TEXT);
            blocks.add(new Block() {
                public int height() { return 118; }
                public void draw(Canvas cv, int top) { cv.drawText(title, PAD + 6, top + 88, p); }
            });
        }

        void note(String text) {
            Paint p = paint(ui.regular, 38, MUTED);
            blocks.add(new Block() {
                public int height() { return 140; }
                public void draw(Canvas cv, int top) { cv.drawText(text, PAD + 6, top + 90, p); }
            });
        }

        void footer(String text) {
            Paint p = paint(ui.regular, 30, MUTED);
            p.setTextAlign(Paint.Align.CENTER);
            blocks.add(new Block() {
                public int height() { return 150; }
                public void draw(Canvas cv, int top) { cv.drawText(text, W / 2f, top + 90, p); }
            });
        }

        void card(List<Row> rows) {
            if (rows.isEmpty()) return;
            TextPaint bigP = textPaint(ui.boardSemi, 60, TEXT);
            TextPaint smallP = textPaint(ui.regular, 30, MUTED);
            TextPaint mainP = textPaint(ui.medium, 38, TEXT);
            TextPaint subP = textPaint(ui.regular, 31, MUTED);
            int textX = CARD_X + IN + LEFT_COL;
            int textW = CARD_X + CARD_W - IN - textX;

            List<StaticLayout[]> layouts = new ArrayList<>();
            List<Integer> heights = new ArrayList<>();
            int total = 0;
            for (Row r : rows) {
                subP.setColor(r.subColor);
                StaticLayout main = layout(r.main, mainP, textW, 3);
                StaticLayout sub = r.sub == null ? null : layout(r.sub, new TextPaint(subP), textW, 8);
                int h = main.getHeight() + (sub != null ? 8 + sub.getHeight() : 0) + (r.bar >= 0 ? 34 : 0);
                h = Math.max(h, 110) + 2 * 34;
                layouts.add(new StaticLayout[] { main, sub });
                heights.add(h);
                total += h;
            }
            final int cardH = total;
            blocks.add(new Block() {
                public int height() { return cardH; }
                public void draw(Canvas cv, int top) {
                    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fill.setColor(CARD);
                    RectF box = new RectF(CARD_X, top, CARD_X + CARD_W, top + cardH);
                    cv.drawRoundRect(box, 40, 40, fill);
                    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(2);
                    stroke.setColor(LINE);
                    cv.drawRoundRect(box, 40, 40, stroke);

                    int y = top;
                    for (int i = 0; i < rows.size(); i++) {
                        Row r = rows.get(i);
                        int h = heights.get(i);
                        if (i > 0) {
                            fill.setColor(LINE);
                            cv.drawRect(CARD_X + IN, y, CARD_X + CARD_W, y + 2, fill);
                        }
                        int rowTop = y + 34;

                        if (r.big != null) {
                            bigP.setColor(r.bigColor);
                            cv.drawText(r.big, CARD_X + IN, rowTop + 52, bigP);
                        }
                        if (r.small != null && !r.small.isEmpty()) {
                            cv.drawText(r.small, CARD_X + IN, rowTop + 98, smallP);
                        }

                        StaticLayout main = layouts.get(i)[0];
                        StaticLayout sub = layouts.get(i)[1];
                        cv.save();
                        cv.translate(textX, rowTop);
                        main.draw(cv);
                        int ty = main.getHeight();
                        if (sub != null) {
                            cv.translate(0, ty + 8);
                            sub.draw(cv);
                            ty += 8 + sub.getHeight();
                        }
                        cv.restore();

                        if (r.bar >= 0) {
                            float by = rowTop + ty + 20;
                            fill.setColor(TRACK);
                            cv.drawRoundRect(new RectF(textX, by, textX + textW, by + 12), 6, 6, fill);
                            fill.setColor(r.barColor);
                            cv.drawRoundRect(new RectF(textX, by, textX + Math.max(12, textW * r.bar), by + 12), 6, 6, fill);
                            if (r.marker75) {
                                fill.setColor(TEXT);
                                float mx = textX + textW * 0.75f;
                                cv.drawRect(mx - 1.5f, by - 6, mx + 1.5f, by + 18, fill);
                            }
                        }
                        y += h;
                    }
                }
            });
        }

        File save(String name) throws Exception {
            int height = 60;   // the header bleeds up into this
            for (Block b : blocks) height += b.height();
            Bitmap bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            cv.drawColor(BG);
            int y = 60;
            for (Block b : blocks) {
                b.draw(cv, y);
                y += b.height();
            }
            return saveBitmap(c, bmp, name);
        }
    }

    /** The navy top part of every share image. */
    private static void drawHeader(Canvas cv, Ui ui, Bitmap icon, int width, int top, String title, String subtitle) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(NAVY);
        cv.drawRoundRect(new RectF(0, top - 60, width, top + 360), 56, 56, bg);
        cv.drawRect(0, top - 60, width, top + 100, bg);   // square top corners
        int y = top + 30;
        if (icon != null) cv.drawBitmap(icon, null, new Rect(PAD, y, PAD + 72, y + 72), null);
        cv.drawText("Srm Sync", PAD + (icon != null ? 96 : 0), y + 50, paint(ui.semibold, 34, ON_NAVY_MUTED));
        cv.drawText(title, PAD, y + 200, paint(ui.boardBold, 104, ON_NAVY));
        cv.drawText(subtitle, PAD, y + 268, paint(ui.medium, 38, ON_NAVY_MUTED));
    }

    private static File saveBitmap(Context c, Bitmap bmp, String name) throws Exception {
        File dir = ShareProvider.dir(c);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        String stamp = SilentScheduler.clock(System.currentTimeMillis(), "yyyyMMdd-HHmm");
        File out = new File(dir, "srm-sync-" + name + "-" + stamp + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
        return out;
    }

    // ================================================================== helpers

    private static StaticLayout layout(String text, TextPaint p, int width, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0, 1.12f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build();
    }

    private static Paint paint(Typeface tf, float px, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(px);
        p.setColor(color);
        return p;
    }

    private static TextPaint textPaint(Typeface tf, float px, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(px);
        p.setColor(color);
        return p;
    }

    private static Bitmap loadIcon(Context c) {
        int id = c.getResources().getIdentifier("ic_launcher", "mipmap", c.getPackageName());
        return id == 0 ? null : BitmapFactory.decodeResource(c.getResources(), id);
    }

    static List<Timetable.Block> forDay(List<Timetable.Block> all, int day) {
        List<Timetable.Block> out = new ArrayList<>();
        for (Timetable.Block b : all) if (b.day == day) out.add(b);
        return out;
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    static String dayName(int day) {
        return DAYS[day];
    }

    static int[] week() {
        return WEEK.clone();
    }
}
