package com.srmsync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** The three share buttons: what each offers, and handing the result to Android's share sheet. */
final class ShareMenu {

    private ShareMenu() {}

    /** Opened from the "Share" button in the website's menu. */
    static void open(MainActivity a) {
        new AlertDialog.Builder(a)
            .setTitle("Share")
            .setItems(new String[] { "Timetable", "Attendance", "Internal marks" }, (d, which) -> {
                if (which == 0) timetable(a);
                else if (which == 1) attendance(a);
                else marks(a);
            })
            .show();
    }

    // ------------------------------------------------------------------ timetable

    static void timetable(MainActivity a) {
        Prefs p = new Prefs(a);
        List<Timetable.Block> all = Timetable.fromJson(p.blocksJson());
        if (all.isEmpty()) {
            missing(a, "timetable");
            return;
        }
        String[] options = { "Whole week as a table (image)", "Today as image", "Whole week as text" };
        new AlertDialog.Builder(a)
            .setTitle("Share timetable")
            .setItems(options, (d, which) -> {
                if (which == 2) {
                    shareText(a, "Share timetable", timetableText(all));
                } else {
                    boolean today = which == 1;
                    shareImage(a, "Share timetable", () -> ShareCards.timetable(a, all, today));
                }
            })
            .show();
    }

    static String timetableText(List<Timetable.Block> all) {
        StringBuilder sb = new StringBuilder("My timetable (Srm Sync)\n");
        for (int day : ShareCards.week()) {
            List<Timetable.Block> list = ShareCards.forDay(all, day);
            if (list.isEmpty()) continue;
            sb.append("\n*").append(ShareCards.dayName(day)).append("*\n");
            for (Timetable.Block b : list) {
                sb.append(Timetable.hhmm(b.start)).append("\u2013").append(Timetable.hhmm(b.end))
                  .append("  ").append(b.label);
                if (!b.room.isEmpty()) sb.append("  (").append(b.room).append(")");
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ attendance

    static void attendance(MainActivity a) {
        Prefs p = new Prefs(a);
        List<AttendanceWatch.Subject> list = AttendanceWatch.saved(p);
        if (list.isEmpty()) {
            missing(a, "attendance");
            return;
        }
        long at = p.attendanceAt();
        chooseImageOrText(a, "Share attendance",
            () -> ShareCards.attendance(a, list, at),
            () -> attendanceText(list, at));
    }

    static String attendanceText(List<AttendanceWatch.Subject> list, long at) {
        StringBuilder sb = new StringBuilder("My attendance, ")
            .append(SilentScheduler.clock(at, "d MMM")).append(" (Srm Sync)\n\n");
        for (AttendanceWatch.Subject s : list) {
            String mark = s.state == AttendanceWatch.LOW ? "\u274C" : s.state == AttendanceWatch.EDGE ? "\u26A0\uFE0F" : "\u2705";
            sb.append(mark).append(' ').append(s.name.isEmpty() ? s.code : s.name)
              .append(": ").append((long) Math.floor(s.percent)).append("% (")
              .append(s.present).append('/').append(s.total).append("), ");
            if (s.state == AttendanceWatch.LOW) sb.append("need ").append(s.needed).append(" more");
            else sb.append("can miss ").append(s.canMiss);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ marks

    static void marks(MainActivity a) {
        Prefs p = new Prefs(a);
        List<Marks.Subject> list = Marks.saved(p);
        if (list.isEmpty()) {
            missing(a, "marks");
            return;
        }
        long at = p.marksAt();
        chooseImageOrText(a, "Share marks",
            () -> ShareCards.marks(a, list, at),
            () -> marksText(list, at));
    }

    static String marksText(List<Marks.Subject> list, long at) {
        StringBuilder sb = new StringBuilder("My internal marks, ")
            .append(SilentScheduler.clock(at, "d MMM")).append(" (Srm Sync)\n");
        for (Marks.Subject m : list) {
            sb.append('\n').append(m.name.isEmpty() ? m.code : m.name).append(": ")
              .append(Marks.num(m.obtained));
            if (!Double.isNaN(m.max)) sb.append('/').append(Marks.num(m.max));
            sb.append('\n');
            for (String[] part : m.parts) sb.append("   ").append(part[0]).append(": ").append(part[1]).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ plumbing

    private interface ImageJob { File make() throws Exception; }
    private interface TextJob { String make(); }

    private static void chooseImageOrText(Activity a, String title, ImageJob image, TextJob text) {
        new AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(new String[] { "As image", "As text" }, (d, which) -> {
                if (which == 0) shareImage(a, title, image);
                else shareText(a, title, text.make());
            })
            .show();
    }

    private static void shareImage(Activity a, String title, ImageJob job) {
        Toast.makeText(a, "Making the image\u2026", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File f = job.make();
                Uri uri = ShareProvider.uriFor(f.getName());
                a.runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri(f.getName(), uri));   // lets the share sheet show a preview
                    launch(a, send, title);
                });
            } catch (Exception | OutOfMemoryError e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Couldn't make the image. Try sharing as text.",
                    Toast.LENGTH_LONG).show());
            }
        }, "share-image").start();
    }

    private static void shareText(Activity a, String title, String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        launch(a, send, title);
    }

    private static void launch(Activity a, Intent send, String title) {
        try {
            a.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No app on this phone can share that.", Toast.LENGTH_LONG).show();
        }
    }

    /** Nothing saved yet: fetch it now through the website. */
    private static void missing(MainActivity a, String what) {
        new AlertDialog.Builder(a)
            .setTitle("No " + what + " yet")
            .setMessage("Srm Sync saves your " + what + " in the background while you're logged in. "
                + "Fetch it now, then try sharing again in a few seconds.")
            .setPositiveButton("Fetch now", (d, w) -> a.requestSync())
            .setNegativeButton("Cancel", null)
            .show();
    }
}
