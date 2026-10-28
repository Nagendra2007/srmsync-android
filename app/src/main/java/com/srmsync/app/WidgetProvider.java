package com.srmsync.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Home-screen widget: what's happening now, or the next class, with its room.
 * It is redrawn every time the class timer runs (class start, class end, reminder),
 * so it stays correct without polling.
 */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
    }

    @Override
    public void onEnabled(Context context) {
        SilentScheduler.refresh(context);
    }

    static void updateAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        if (manager == null) return;
        int[] ids = manager.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        render(c, manager, ids);
    }

    private static void render(Context c, AppWidgetManager manager, int[] ids) {
        String pkg = c.getPackageName();
        int layout = id(c, "widget_next", "layout");
        if (layout == 0) return;

        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        List<Timetable.Block> blocks = Timetable.fromJson(p.blocksJson());

        String status;
        int statusColor = Color.parseColor("#9FB0CB");
        String time;
        String label;
        String room = "";

        if (blocks.isEmpty()) {
            status = "Srm Sync";
            time = "No timetable";
            label = "Open the app and log in once.";
        } else {
            List<SilentScheduler.Occurrence> classes = SilentScheduler.occurrences(blocks, p.skipDate(), now);
            SilentScheduler.Occurrence cur = null;
            SilentScheduler.Occurrence next = null;
            for (SilentScheduler.Occurrence o : classes) {
                if (o.start <= now && now < o.end && cur == null) cur = o;
                if (o.start > now && next == null) next = o;
            }
            boolean silentNow = p.appSilenced() && (cur != null || now < p.testUntil());

            if (cur != null) {
                status = silentNow ? "Silent now" : "In class now";
                statusColor = Color.parseColor("#FF6B6F");
                time = "till " + Timetable.hhmm(cur.block.end);
                label = cur.block.label;
                room = cur.block.room;
            } else if (next != null) {
                boolean today = SilentScheduler.dateKey(next.start).equals(SilentScheduler.dateKey(now));
                status = !p.enabled() ? "Next class (auto-silent off)"
                    : today ? "Next class" : "Next class, " + SilentScheduler.clock(next.start, "EEEE");
                statusColor = Color.parseColor("#7FB2FF");
                time = Timetable.hhmm(next.block.start);
                label = next.block.label;
                room = next.block.room;
            } else {
                status = "Next class";
                time = "None soon";
                label = "No classes in the next 7 days.";
            }
            if (p.skipDate().equals(SilentScheduler.dateKey(now)) && cur == null) {
                status = "Skipping today \u00b7 " + status.toLowerCase();
            }
        }

        RemoteViews v = new RemoteViews(pkg, layout);
        v.setTextViewText(id(c, "widget_status", "id"), status);
        v.setTextColor(id(c, "widget_status", "id"), statusColor);
        v.setTextViewText(id(c, "widget_time", "id"), time);
        v.setTextViewText(id(c, "widget_label", "id"), label);
        v.setTextViewText(id(c, "widget_room", "id"), room.isEmpty() ? "" : "Room " + room);

        Intent open = new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 400, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(id(c, "widget_root", "id"), pi);

        manager.updateAppWidget(ids, v);
    }

    /** Looks up a resource by name (keeps the Java code free of the generated R class). */
    private static int id(Context c, String name, String type) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }
}
