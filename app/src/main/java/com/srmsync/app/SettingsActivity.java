package com.srmsync.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/** The "Class silent mode" screen. Built in code (no layout files) to keep the project small. */
public class SettingsActivity extends Activity {

    private LinearLayout box;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(40));
        scroll.addView(box);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permissions may have changed while the student was in Android settings.
        SilentScheduler.refresh(this);
        render();
    }

    private void changed() {
        SilentScheduler.refresh(this);
        render();
    }

    private void render() {
        box.removeAllViews();

        // ---------- status ----------
        heading("Right now");
        text(SilentScheduler.describe(this), 15);

        // ---------- permissions ----------
        heading("Setup");
        permissionRow("Do Not Disturb access",
            "Needed to switch your phone to silent and back.",
            SilentScheduler.hasDndAccess(this),
            () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        if (Build.VERSION.SDK_INT >= 31) {
            permissionRow("On-time alarms",
                "So silence starts at the bell, not a few minutes late.",
                SilentScheduler.canUseExactAlarms(this),
                () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()))));
        }

        PowerManager pm = getSystemService(PowerManager.class);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        permissionRow("Battery: unrestricted",
            "Stops battery saver from killing the class timer.",
            unrestricted,
            () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));

        text("On Xiaomi, Redmi, Realme, Vivo, Oppo or OnePlus phones, also open App info \u2192 Battery "
            + "and pick \u201cNo restrictions\u201d, and switch on \u201cAutostart\u201d if you see it.", 13);
        Button appInfo = button("Open app info");
        appInfo.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));

        // ---------- options ----------
        heading("Options");

        Switch enabled = new Switch(this);
        enabled.setText("Auto-silent during classes");
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        enabled.setChecked(prefs.enabled());
        enabled.setOnCheckedChangeListener((b, on) -> { prefs.setEnabled(on); changed(); });
        add(enabled);

        RadioGroup mode = new RadioGroup(this);
        RadioButton silent = new RadioButton(this);
        silent.setText("Silent (no sound, no vibration)");
        silent.setId(View.generateViewId());
        RadioButton vibrate = new RadioButton(this);
        vibrate.setText("Vibrate only");
        vibrate.setId(View.generateViewId());
        mode.addView(silent);
        mode.addView(vibrate);
        mode.check(prefs.vibrateMode() ? vibrate.getId() : silent.getId());
        mode.setOnCheckedChangeListener((g, id) -> { prefs.setVibrateMode(id == vibrate.getId()); changed(); });
        add(mode);

        CheckBox breaks = new CheckBox(this);
        breaks.setText("Stay silent during short breaks (10 min or less)");
        breaks.setChecked(prefs.mergeBreaks());
        breaks.setOnCheckedChangeListener((b, on) -> { prefs.setMergeBreaks(on); changed(); });
        add(breaks);

        // ---------- today ----------
        heading("Today");
        String today = SilentScheduler.dateKey(System.currentTimeMillis());
        boolean skipping = today.equals(prefs.skipDate());
        Button skip = button(skipping ? "Resume auto-silent today" : "Skip today (holiday / no classes)");
        skip.setOnClickListener(v -> { prefs.setSkipDate(skipping ? "" : today); changed(); });

        Button test = button("Test: go silent for 1 minute");
        test.setOnClickListener(v -> {
            if (!SilentScheduler.hasDndAccess(this)) {
                toast("Give \u201cDo Not Disturb access\u201d first (Setup, above).");
                return;
            }
            prefs.setTestUntil(System.currentTimeMillis() + 60_000L);
            changed();
            toast("Silent now. It should switch back by itself in about a minute.");
        });

        // ---------- timetable ----------
        heading("Timetable");
        long last = prefs.lastSyncAt();
        text(last == 0 ? "Not synced yet." : "Last synced: " + SilentScheduler.clock(last, "d MMM, HH:mm"), 13);

        String lastMsg = prefs.lastSyncMessage();
        if (!lastMsg.isEmpty()) text("Last attempt: " + lastMsg, 13);

        Button sync = button("Sync timetable now");
        sync.setOnClickListener(v -> {
            // The sync runs through the website page, so go back to it.
            prefs.setSyncRequested(true);
            finish();
        });

        showWeek();
    }

    private void showWeek() {
        List<Timetable.Block> blocks = Timetable.fromJson(prefs.blocksJson());
        if (blocks.isEmpty()) return;

        heading("Your week");
        int[] order = { Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY };
        String[] names = { "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };

        for (int day : order) {
            StringBuilder sb = new StringBuilder();
            for (Timetable.Block b : blocks) {
                if (b.day != day) continue;
                sb.append(Timetable.hhmm(b.start)).append("\u2013").append(Timetable.hhmm(b.end))
                  .append("   ").append(b.label).append('\n');
            }
            if (sb.length() == 0) continue;
            TextView dayName = text(names[day], 15);
            dayName.setTypeface(Typeface.DEFAULT_BOLD);
            dayName.setPadding(0, dp(10), 0, 0);
            TextView rows = text(sb.toString().trim(), 13);
            rows.setTypeface(Typeface.MONOSPACE);
        }
    }

    // ------------------------------------------------------------------ little UI builders

    private void permissionRow(String title, String why, boolean ok, Runnable fix) {
        TextView t = text((ok ? "\u2705  " : "\u274C  ") + title, 16);
        t.setPadding(0, dp(8), 0, 0);
        text(why, 13);
        if (!ok) {
            Button b = button("Allow");
            b.setOnClickListener(v -> fix.run());
        }
    }

    private void heading(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(22), 0, dp(4));
        t.setAlpha(0.7f);
        add(t);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.15f);
        add(t);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        add(b);
        return b;
    }

    private void add(View v) {
        box.addView(v, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            // Some phone brands hide the exact screen; fall back to the app's info page.
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
                toast("Couldn't open that settings screen on this phone.");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
