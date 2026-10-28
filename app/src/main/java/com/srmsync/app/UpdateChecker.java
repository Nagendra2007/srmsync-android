package com.srmsync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app isn't on the Play Store, so nothing updates it automatically.
 * On opening, this asks GitHub "what's the newest build?" and, if it's newer
 * than this one, offers to download it.
 *
 * The GitHub release notes contain lines like "versionCode: 5" and
 * "versionName: 1.4" (the build workflow writes them), which is how we compare.
 */
final class UpdateChecker {

    private static final String RELEASE_API =
        "https://api.github.com/repos/Nagendra2007/srmsync-android/releases/tags/apk-latest";
    private static final String DOWNLOAD_PAGE = "https://nagendra2007.github.io/srmsync-android/";

    private static final long CHECK_EVERY_MS = 6 * 60 * 60_000L;      // at most every 6 hours
    private static final long REMIND_AFTER_MS = 24 * 60 * 60_000L;    // "Later" = ask again tomorrow

    private static final Pattern CODE = Pattern.compile("versionCode:\\s*(\\d+)");
    private static final Pattern NAME = Pattern.compile("versionName:\\s*([0-9A-Za-z._-]+)");

    private UpdateChecker() {}

    static void maybeCheck(Activity activity) {
        Prefs p = new Prefs(activity);
        long now = System.currentTimeMillis();
        if (now - p.lastUpdateCheck() < CHECK_EVERY_MS) return;

        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(get(RELEASE_API));
                p.setLastUpdateCheck(System.currentTimeMillis());

                String notes = release.optString("body", "");
                Matcher code = CODE.matcher(notes);
                if (!code.find()) return;
                int latest = Integer.parseInt(code.group(1));
                Matcher name = NAME.matcher(notes);
                String latestName = name.find() ? name.group(1) : String.valueOf(latest);

                PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long current = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : legacyCode(info);
                if (latest <= current) return;
                if (latest == p.dismissedVersion() && now - p.dismissedAt() < REMIND_AFTER_MS) return;

                // Prefer the download page (served from github.io); fall back to the release page.
                String url = reachable(DOWNLOAD_PAGE) ? DOWNLOAD_PAGE : release.optString("html_url", DOWNLOAD_PAGE);
                String currentName = info.versionName == null ? "" : info.versionName;

                activity.runOnUiThread(() -> show(activity, p, latest, latestName, currentName, url));
            } catch (Exception ignored) {
                // No internet or GitHub unreachable: just try again next time the app opens.
            }
        }, "update-check").start();
    }

    private static void show(Activity a, Prefs p, int latest, String latestName, String currentName, String url) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a)
            .setTitle("Update available")
            .setMessage("Srm Sync " + latestName + " is ready. You have " + currentName + ".\n\n"
                + "Download it, then install it over this version. Your settings and timetable stay.")
            .setPositiveButton("Download", (d, w) -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));   // opens in Chrome
                } catch (ActivityNotFoundException ignored) {
                    // no browser installed; nothing more we can do
                }
            })
            .setNegativeButton("Later", (d, w) -> p.setDismissedUpdate(latest, System.currentTimeMillis()))
            .setCancelable(true)
            .show();
    }

    @SuppressWarnings("deprecation")
    private static long legacyCode(PackageInfo info) {
        return info.versionCode;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
        try {
            con.setConnectTimeout(15_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "SrmSyncApp");
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private static boolean reachable(String address) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(8_000);
            con.setReadTimeout(8_000);
            int code = con.getResponseCode();
            con.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
