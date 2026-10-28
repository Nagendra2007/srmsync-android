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
import java.util.List;

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
            s.header("My timetable", plural(all.size(), "class", "classes") + " a week");
            for (int day : WEEK) {
                List<Timetable.Block> list = forDay(all, day);
                if (list.isEmpty()) continue;
                s.section(DAYS[day]);
                s.card(classRows(list));
            }
        }
        s.footer("Shared from Srm Sync");
        return s.save("timetable");
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
            Paint brand = paint(ui.semibold, 34, ON_NAVY_MUTED);
            Paint big = paint(ui.boardBold, 104, ON_NAVY);
            Paint sub = paint(ui.medium, 38, ON_NAVY_MUTED);
            blocks.add(new Block() {
                public int height() { return 410; }   // 360 of navy + a 50 px gap before the first card
                public void draw(Canvas cv, int top) {
                    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bg.setColor(NAVY);
                    cv.drawRoundRect(new RectF(0, top - 60, W, top + 360), 56, 56, bg);
                    cv.drawRect(0, top - 60, W, top + 100, bg);   // square top corners
                    int y = top + 30;
                    if (icon != null) {
                        cv.drawBitmap(icon, null, new Rect(PAD, y, PAD + 72, y + 72), null);
                    }
                    cv.drawText("Srm Sync", PAD + (icon != null ? 96 : 0), y + 50, brand);
                    cv.drawText(title, PAD, y + 200, big);
                    cv.drawText(subtitle, PAD, y + 268, sub);
                }
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
