package com.srmsync.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The class-silent screen.
 * Top: a navy "status board" that says what the phone is doing right now,
 * with today's classes on a strip. Below: today's list, setup, options, the week.
 */
public class SettingsActivity extends Activity {

    private static final int AMBER = Color.parseColor("#F5A524");
    private static final int BLUE = Color.parseColor("#2F6FDB");
    private static final String[] DAY_SHORT = { "", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

    private Prefs prefs;
    private Ui ui;
    private LinearLayout page;
    private int selectedDay = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Redraw every 30 s so "now" and the status stay current while the screen is open. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ui = Ui.get(this);

        Window w = getWindow();
        w.setStatusBarColor(ui.hero);
        w.setNavigationBarColor(ui.bg);
        int flags = 0;   // light icons on the navy status bar
        if (!ui.dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, dp(36));
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SilentScheduler.refresh(this);   // permissions may have changed in Android settings
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void changed() {
        SilentScheduler.refresh(this);
        render();
    }

    // ================================================================== layout

    private void render() {
        page.removeAllViews();

        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_WEEK);
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        boolean skipping = SilentScheduler.dateKey(now).equals(prefs.skipDate());

        List<Timetable.Block> all = Timetable.fromJson(prefs.blocksJson());
        List<Timetable.Block> todays = forDay(all, today);
        if (selectedDay == 0) selectedDay = todays.isEmpty() ? firstDayWithClasses(all) : today;

        page.addView(hero(now, nowMin, todays, all.isEmpty(), skipping));

        if (!allPermissionsOk()) setupSection();
        if (!all.isEmpty()) todaySection(todays, nowMin, skipping);
        shareSection();
        optionsSection();
        if (!all.isEmpty()) remindersSection();
        attendanceSection();
        if (!all.isEmpty()) weekSection(all);
        footer();
    }

    // ------------------------------------------------------------------ the status board

    private View hero(long now, int nowMin, List<Timetable.Block> todays, boolean noTimetable, boolean skipping) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(24));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(ui.hero);
        float r = dp(28);
        bgShape.setCornerRadii(new float[] { 0, 0, 0, 0, r, r, r, r });
        box.setBackground(bgShape);

        // Top bar: back, title, master switch.
        LinearLayout bar = row();
        TextView back = tv("\u2190", 24, ui.medium, ui.heroText);
        back.setPadding(0, dp(8), dp(14), dp(8));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = tv("Class silent", 18, ui.semibold, ui.heroText);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch master = new Switch(this);
        master.setChecked(prefs.enabled());
        master.setContentDescription("Auto-silent during classes");
        tintSwitch(master, Ui.RED, ui.heroTrack);
        master.setOnCheckedChangeListener((b, on) -> { prefs.setEnabled(on); changed(); });
        bar.addView(master);
        box.addView(bar);

        // Work out what to say.
        String pill;
        int pillColor;
        String big;
        String sub;
        List<SilentScheduler.Interval> list = prefs.enabled()
            ? SilentScheduler.upcoming(prefs, now) : Collections.emptyList();
        SilentScheduler.Interval cur = SilentScheduler.current(list, now);
        SilentScheduler.Interval next = SilentScheduler.nextClass(list, now);

        if (now < prefs.testUntil()) {
            pill = "Test running"; pillColor = Ui.RED;
            big = "till " + SilentScheduler.clock(prefs.testUntil(), "HH:mm:ss");
            sub = "Your phone switches back by itself.";
        } else if (noTimetable) {
            pill = "No timetable yet"; pillColor = 0;
            big = "Log in once";
            sub = "Open Srm Sync and log in. Your classes load by themselves.";
        } else if (!prefs.enabled()) {
            pill = "Auto-silent off"; pillColor = 0;
            big = "Rings in class";
            sub = "Turn on the switch above to silence your classes.";
        } else if (cur != null) {
            pill = "Silent now"; pillColor = Ui.RED;
            big = "till " + SilentScheduler.clock(cur.end, "HH:mm");
            sub = cur.label;
        } else if (next != null) {
            pill = skipping ? "Skipping today" : "Ringer on"; pillColor = skipping ? 0 : BLUE;
            boolean sameDay = SilentScheduler.dateKey(next.start).equals(SilentScheduler.dateKey(now));
            big = sameDay ? "at " + SilentScheduler.clock(next.start, "HH:mm")
                          : SilentScheduler.clock(next.start, "EEE HH:mm");
            sub = "Next silent: " + next.label;
        } else {
            pill = "Ringer on"; pillColor = BLUE;
            big = "No classes";
            sub = "Nothing in the next 7 days.";
        }
        if (prefs.enabled() && !noTimetable && !SilentScheduler.hasDndAccess(this)) {
            pill = "Needs permission"; pillColor = AMBER;
            sub = "Allow Do Not Disturb access below, or your phone can't be silenced.";
        }

        LinearLayout status = statusPill(pill, pillColor);
        LinearLayout.LayoutParams plp = wrap();
        plp.topMargin = dp(14);
        box.addView(status, plp);

        TextView bigText = tv(big, 56, ui.boardBold, ui.heroText);
        bigText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = dp(10);
        box.addView(bigText, blp);

        TextView subText = tv(sub, 16, ui.medium, ui.heroMuted);
        subText.setMaxLines(2);
        subText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = dp(4);
        box.addView(subText, slp);

        if (!todays.isEmpty()) {
            DayStripView strip = new DayStripView(this, ui);
            strip.setDay(todays, nowMin);
            LinearLayout.LayoutParams stlp = full();
            stlp.topMargin = dp(22);
            box.addView(strip, stlp);
        } else if (!noTimetable) {
            TextView none = tv("No classes today", 14, ui.regular, ui.heroMuted);
            LinearLayout.LayoutParams nlp = wrap();
            nlp.topMargin = dp(18);
            box.addView(none, nlp);
        }
        return box;
    }

    private LinearLayout statusPill(String text, int color) {
        LinearLayout p = row();
        p.setPadding(dp(10), dp(5), dp(12), dp(5));
        int bgColor = color == 0 ? Color.parseColor("#2A3A57") : color;
        p.setBackground(round(bgColor, 14));
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color == AMBER ? ui.hero : Color.WHITE);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(7);
        p.addView(dot, dlp);
        p.addView(tv(text, 14, ui.semibold, color == AMBER ? ui.hero : Color.WHITE));
        return p;
    }

    // ------------------------------------------------------------------ setup (only when something is missing)

    private boolean dndOk() { return SilentScheduler.hasDndAccess(this); }
    private boolean exactOk() { return SilentScheduler.canUseExactAlarms(this); }
    private boolean batteryOk() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }
    private boolean notificationsOk() { return Notifier.allowed(this); }
    private boolean allPermissionsOk() { return dndOk() && exactOk() && batteryOk() && notificationsOk(); }

    private void fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotificationPermission()) {
            prefs.setAskedNotificationPermission();
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 7);
        } else if (Build.VERSION.SDK_INT >= 26) {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        changed();
    }

    private void setupSection() {
        section("Finish setup");
        LinearLayout card = card();
        boolean firstRow = true;
        if (!dndOk()) {
            setupRow(card, "Do Not Disturb access", "Lets Srm Sync switch your phone to silent and back.", firstRow,
                () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
            firstRow = false;
        }
        if (!exactOk() && Build.VERSION.SDK_INT >= 31) {
            setupRow(card, "Alarms on time", "Silence starts at the bell, not minutes later.", firstRow,
                () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()))));
            firstRow = false;
        }
        if (!notificationsOk()) {
            setupRow(card, "Notifications", "For the silent-in-class notice, class reminders and attendance warnings.",
                firstRow, this::fixNotifications);
            firstRow = false;
        }
        if (!batteryOk()) {
            setupRow(card, "Battery: no restrictions", "Stops battery saver from stopping the class timer.", firstRow,
                () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))));
        }
        addCard(card);
    }

    private void setupRow(LinearLayout card, String title, String why, boolean first, Runnable fix) {
        if (!first) card.addView(divider());
        LinearLayout r = row();
        r.setPadding(dp(18), dp(14), dp(14), dp(14));
        LinearLayout words = column();
        words.addView(tv(title, 16, ui.semibold, ui.text));
        TextView w = tv(why, 14, ui.regular, ui.muted);
        w.setPadding(0, dp(2), dp(10), 0);
        words.addView(w);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(button("Allow", true, v -> fix.run()));
        card.addView(r);
    }

    // ------------------------------------------------------------------ today

    private void todaySection(List<Timetable.Block> todays, int nowMin, boolean skipping) {
        section("Today");
        LinearLayout card = card();
        if (todays.isEmpty()) {
            TextView none = tv("No classes today. Your phone rings as usual.", 15, ui.regular, ui.muted);
            none.setPadding(dp(18), dp(18), dp(18), dp(18));
            card.addView(none);
        } else {
            boolean nextMarked = false;
            for (int i = 0; i < todays.size(); i++) {
                Timetable.Block b = todays.get(i);
                String tag;
                if (skipping) {
                    tag = nowMin >= b.end ? "Done" : "Skipped";
                } else if (nowMin >= b.end) {
                    tag = "Done";
                } else if (nowMin >= b.start) {
                    tag = "Now";
                } else if (!nextMarked) {
                    tag = "Next";
                    nextMarked = true;
                } else {
                    tag = "";
                }
                if (i > 0) card.addView(divider());
                card.addView(classRow(b, tag));
            }
        }
        addCard(card);

        LinearLayout actions = row();
        actions.setPadding(dp(16), dp(12), dp(16), 0);
        if (!todays.isEmpty()) {
            String key = SilentScheduler.dateKey(System.currentTimeMillis());
            actions.addView(button(skipping ? "Resume today" : "Skip today", false,
                v -> { prefs.setSkipDate(skipping ? "" : key); changed(); }), weight());
            View gap = new View(this);
            actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        }
        actions.addView(button("Try it for 1 minute", false, v -> {
            if (!dndOk()) {
                toast("Allow Do Not Disturb access first.");
                return;
            }
            prefs.setTestUntil(System.currentTimeMillis() + 60_000L);
            changed();
            toast("Silent now. It switches back in about a minute.");
        }), weight());
        page.addView(actions);
    }

    /** One timetable row: big start time on the left, subject in the middle, a tag on the right. */
    private View classRow(Timetable.Block b, String tag) {
        LinearLayout r = row();
        r.setPadding(dp(18), dp(12), dp(16), dp(12));
        boolean done = tag.equals("Done");

        LinearLayout times = column();
        TextView start = tv(Timetable.hhmm(b.start), 24, ui.boardSemi, done ? ui.muted : ui.text);
        start.setIncludeFontPadding(false);
        times.addView(start);
        times.addView(tv("to " + Timetable.hhmm(b.end), 13, ui.regular, ui.muted));
        r.addView(times, new LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = tv(b.label, 15, ui.medium, done ? ui.muted : ui.text);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (!tag.isEmpty()) {
            TextView t = tv(tag, 13, ui.semibold, ui.muted);
            t.setPadding(dp(10), dp(4), dp(10), dp(4));
            if (tag.equals("Now")) {
                t.setTextColor(Color.WHITE);
                t.setBackground(round(Ui.RED, 12));
            } else if (tag.equals("Next")) {
                t.setTextColor(Ui.RED);
                t.setBackground(stroke(Color.TRANSPARENT, Ui.RED, 12));
            }
            LinearLayout.LayoutParams tlp = wrap();
            tlp.leftMargin = dp(8);
            r.addView(t, tlp);
        }
        return r;
    }

    // ------------------------------------------------------------------ share

    private void shareSection() {
        section("Share");
        LinearLayout tiles = row();
        tiles.setPadding(dp(16), 0, dp(16), 0);
        tiles.addView(shareTile("Timetable", "Week or just today", v -> ShareMenu.timetable(this)), tileLp(false));
        tiles.addView(shareTile("Attendance", "With % and how many you can miss", v -> ShareMenu.attendance(this)), tileLp(true));
        tiles.addView(shareTile("Marks", "Internal marks with the breakdown", v -> ShareMenu.marks(this)), tileLp(true));
        page.addView(tiles);
    }

    private View shareTile(String title, String sub, View.OnClickListener click) {
        LinearLayout t = column();
        t.setPadding(dp(14), dp(14), dp(12), dp(16));
        t.setBackground(rippleStroke(ui.card, ui.line, 18));
        t.setContentDescription("Share " + title);
        TextView arrow = tv("\u2197", 22, ui.semibold, Ui.RED);   // share arrow
        arrow.setIncludeFontPadding(false);
        t.addView(arrow);
        TextView name = tv(title, 16, ui.semibold, ui.text);
        name.setPadding(0, dp(10), 0, dp(2));
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        t.addView(tv(sub, 13, ui.regular, ui.muted));
        t.setOnClickListener(click);
        return t;
    }

    /** Equal-width tiles that all stretch to the tallest one. */
    private LinearLayout.LayoutParams tileLp(boolean gapBefore) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        if (gapBefore) lp.leftMargin = dp(10);
        return lp;
    }

    // ------------------------------------------------------------------ options

    private void optionsSection() {
        section("While in class");
        LinearLayout card = card();

        // Silent | Vibrate
        LinearLayout seg = row();
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(ui.dark ? Color.parseColor("#0A1120") : Color.parseColor("#EEF1F6"), 14));
        seg.addView(segment("Silent", !prefs.vibrateMode(), v -> { prefs.setVibrateMode(false); changed(); }), weight());
        seg.addView(segment("Vibrate", prefs.vibrateMode(), v -> { prefs.setVibrateMode(true); changed(); }), weight());
        LinearLayout segWrap = column();
        segWrap.setPadding(dp(14), dp(14), dp(14), dp(10));
        segWrap.addView(seg);
        card.addView(segWrap);

        card.addView(divider());
        LinearLayout breaks = row();
        breaks.setPadding(dp(18), dp(12), dp(14), dp(12));
        LinearLayout words = column();
        words.addView(tv("Stay silent in short breaks", 16, ui.semibold, ui.text));
        words.addView(tv("Gaps of 10 minutes or less between classes", 14, ui.regular, ui.muted));
        breaks.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.mergeBreaks());
        tintSwitch(sw, Ui.RED, ui.line);
        sw.setOnCheckedChangeListener((b, on) -> { prefs.setMergeBreaks(on); changed(); });
        breaks.addView(sw);
        card.addView(breaks);

        card.addView(divider());
        LinearLayout battery = row();
        battery.setPadding(dp(18), dp(12), dp(18), dp(14));
        battery.setBackground(ripple(Color.TRANSPARENT, 0));
        LinearLayout bw = column();
        bw.addView(tv("Silence starting late?", 16, ui.semibold, ui.text));
        bw.addView(tv("On Xiaomi, Realme, Vivo, Oppo or OnePlus, open App info \u203a Battery and choose "
            + "No restrictions. Turn on Autostart if you see it.", 14, ui.regular, ui.muted));
        battery.addView(bw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView chevron = tv("\u203a", 26, ui.medium, ui.muted);
        chevron.setPadding(dp(10), 0, 0, 0);
        battery.addView(chevron);
        battery.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));
        card.addView(battery);

        addCard(card);
    }

    private TextView segment(String text, boolean selected, View.OnClickListener click) {
        TextView t = tv(text, 15, selected ? ui.semibold : ui.medium, selected ? ui.text : ui.muted);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        if (selected) {
            t.setBackground(round(ui.card, 11));
            t.setElevation(dp(1));
        }
        t.setOnClickListener(click);
        return t;
    }

    // ------------------------------------------------------------------ reminders

    private void remindersSection() {
        section("Reminders");
        LinearLayout card = card();
        card.addView(switchRow("Remind me before class", "Shows the subject and the room number",
            prefs.remindersOn(), on -> { prefs.setRemindersOn(on); changed(); }));

        if (prefs.remindersOn()) {
            card.addView(divider());
            LinearLayout seg = row();
            seg.setPadding(dp(4), dp(4), dp(4), dp(4));
            seg.setBackground(round(ui.dark ? Color.parseColor("#0A1120") : Color.parseColor("#EEF1F6"), 14));
            int current = prefs.reminderMinutes();
            for (int m : new int[] { 5, 10, 15 }) {
                final int minutes = m;
                seg.addView(segment(m + " min before", current == m,
                    v -> { prefs.setReminderMinutes(minutes); changed(); }), weight());
            }
            LinearLayout segWrap = column();
            segWrap.setPadding(dp(14), dp(12), dp(14), dp(14));
            segWrap.addView(seg);
            card.addView(segWrap);
        }
        addCard(card);
    }

    // ------------------------------------------------------------------ attendance

    private void attendanceSection() {
        section("Attendance");
        LinearLayout card = card();
        card.addView(switchRow("Warn me near 75%", "A notification when a subject gets close or drops below",
            prefs.attendanceAlertsOn(), on -> prefs.setAttendanceAlertsOn(on)));

        List<AttendanceWatch.Subject> subjects = AttendanceWatch.saved(prefs);
        if (subjects.isEmpty()) {
            card.addView(divider());
            TextView none = tv("Your subjects show up here after the next sync.", 15, ui.regular, ui.muted);
            none.setPadding(dp(18), dp(14), dp(18), dp(16));
            card.addView(none);
        } else {
            Collections.sort(subjects, (a, b) -> Double.compare(a.percent, b.percent));   // most at risk first
            for (AttendanceWatch.Subject s : subjects) {
                card.addView(divider());
                card.addView(attendanceRow(s));
            }
        }
        addCard(card);

        if (prefs.attendanceAt() > 0) {
            TextView when = tv("Updated " + SilentScheduler.clock(prefs.attendanceAt(), "d MMM, HH:mm"),
                13, ui.regular, ui.muted);
            when.setPadding(dp(22), dp(8), dp(22), 0);
            page.addView(when);
        }
    }

    private View attendanceRow(AttendanceWatch.Subject s) {
        LinearLayout r = row();
        r.setPadding(dp(18), dp(12), dp(16), dp(12));

        int color = s.state == AttendanceWatch.LOW ? Ui.RED
            : s.state == AttendanceWatch.EDGE ? AMBER : ui.text;
        TextView pct = tv(String.valueOf(Math.round(Math.floor(s.percent))) + "%", 26, ui.boardSemi, color);
        pct.setIncludeFontPadding(false);
        r.addView(pct, new LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout words = column();
        TextView name = tv(s.shortName(), 15, ui.medium, ui.text);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        words.addView(tv(s.present + " of " + s.total + ". " + s.advice(), 13, ui.regular, ui.muted));
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private interface OnToggle { void set(boolean on); }

    private View switchRow(String title, String subtitle, boolean checked, OnToggle onToggle) {
        LinearLayout r = row();
        r.setPadding(dp(18), dp(12), dp(14), dp(12));
        LinearLayout words = column();
        words.addView(tv(title, 16, ui.semibold, ui.text));
        words.addView(tv(subtitle, 14, ui.regular, ui.muted));
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        tintSwitch(sw, Ui.RED, ui.line);
        sw.setOnCheckedChangeListener((b, on) -> onToggle.set(on));
        r.addView(sw);
        return r;
    }

    // ------------------------------------------------------------------ week

    private void weekSection(List<Timetable.Block> all) {
        section("Your week");

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        chips.setPadding(dp(16), 0, dp(16), dp(12));
        int[] order = { Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY };
        for (int day : order) {
            if (forDay(all, day).isEmpty()) continue;
            boolean sel = day == selectedDay;
            TextView chip = tv(DAY_SHORT[day], 15, ui.semibold, sel ? Color.WHITE : ui.text);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setBackground(sel ? round(Ui.RED, 18) : stroke(ui.card, ui.line, 18));
            final int d = day;
            chip.setOnClickListener(v -> { selectedDay = d; render(); });
            LinearLayout.LayoutParams clp = wrap();
            clp.rightMargin = dp(8);
            chips.addView(chip, clp);
        }
        chipsScroll.addView(chips);
        page.addView(chipsScroll);

        LinearLayout card = card();
        List<Timetable.Block> day = forDay(all, selectedDay);
        for (int i = 0; i < day.size(); i++) {
            if (i > 0) card.addView(divider());
            card.addView(classRow(day.get(i), ""));
        }
        addCard(card);
    }

    // ------------------------------------------------------------------ footer

    private void footer() {
        LinearLayout f = column();
        f.setGravity(Gravity.CENTER_HORIZONTAL);
        f.setPadding(dp(24), dp(26), dp(24), 0);

        String msg = prefs.lastSyncMessage();
        long last = prefs.lastSyncAt();
        String line = !msg.isEmpty() ? msg
            : last == 0 ? "Timetable not synced yet."
            : "Timetable synced " + SilentScheduler.clock(last, "d MMM, HH:mm") + ".";
        TextView info = tv(line, 13, ui.regular, ui.muted);
        info.setGravity(Gravity.CENTER);
        f.addView(info);

        TextView sync = tv("Sync timetable now", 15, ui.semibold, Ui.RED);
        sync.setPadding(dp(16), dp(10), dp(16), dp(10));
        sync.setBackground(ripple(Color.TRANSPARENT, 18));
        sync.setOnClickListener(v -> {
            prefs.setSyncRequested(true);   // the main screen does the sync through the website
            finish();
        });
        f.addView(sync);
        page.addView(f);
    }

    // ================================================================== small builders

    private void section(String title) {
        TextView t = tv(title, 19, ui.semibold, ui.text);
        t.setPadding(dp(20), dp(28), dp(20), dp(10));
        page.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(stroke(ui.card, ui.line, 20));
        c.setClipToOutline(true);
        return c;
    }

    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams lp = full();
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        page.addView(card, lp);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(ui.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(18);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView button(String text, boolean filled, View.OnClickListener click) {
        TextView b = tv(text, 15, ui.semibold, filled ? Color.WHITE : ui.text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setBackground(filled ? ripple(Ui.RED, 22) : rippleStroke(ui.card, ui.line, 22));
        b.setOnClickListener(click);
        return b;
    }

    private TextView tv(String text, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable stroke(int fill, int strokeColor, int radiusDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(Math.max(1, dp(1)), strokeColor);
        return g;
    }

    private Drawable ripple(int fill, int radiusDp) {
        int rippleColor = ui.dark ? Color.parseColor("#33FFFFFF") : Color.parseColor("#1F13203A");
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), round(fill, radiusDp), round(Color.WHITE, radiusDp));
    }

    private Drawable rippleStroke(int fill, int strokeColor, int radiusDp) {
        int rippleColor = ui.dark ? Color.parseColor("#33FFFFFF") : Color.parseColor("#1F13203A");
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), stroke(fill, strokeColor, radiusDp),
            round(Color.WHITE, radiusDp));
    }

    private void tintSwitch(Switch s, int onColor, int offTrack) {
        int[][] states = { { android.R.attr.state_checked }, {} };
        s.setThumbTintList(new ColorStateList(states, new int[] { Color.WHITE, Color.parseColor("#E9EDF3") }));
        s.setTrackTintList(new ColorStateList(states, new int[] { onColor, offTrack }));
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    // ================================================================== helpers

    private static List<Timetable.Block> forDay(List<Timetable.Block> all, int day) {
        List<Timetable.Block> out = new ArrayList<>();
        for (Timetable.Block b : all) if (b.day == day) out.add(b);
        return out;
    }

    /** Monday-first: the first day of the week that has any classes. */
    private static int firstDayWithClasses(List<Timetable.Block> all) {
        int[] order = { Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY };
        for (int day : order) {
            if (!forDay(all, day).isEmpty()) return day;
        }
        return Calendar.MONDAY;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
                toast("This phone doesn't have that settings screen.");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
