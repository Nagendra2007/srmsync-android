# SRMSync Android app

Shows srmsync.in inside an app, and puts your phone on silent during classes
using your SRM timetable, then switches it back when the class ends.

## Getting the APK

1. Push this folder to its own GitHub repo (branch `main`).
2. On GitHub, open the **Actions** tab. A "Build Android APK" run starts by itself (about 3–5 minutes).
3. When it shows a green tick, open the repo's **Releases** section → **SRMSync APK (latest)** →
   download `SRMSync.apk` on your phone.
4. Tap the file. Android asks to allow installing from this source once; allow it, then install.

Every later push to `main` builds a fresh APK the same way.
New versions install over the old one because the app is always signed with `app/srmsync.keystore`.

## How it works

| File | Job |
|---|---|
| `MainActivity.java` | The website in a WebView, plus the red 🔕 button |
| `TimetableSync.java` | After you log in, calls `GET /api/timetable` with your session |
| `Timetable.java` | Turns that JSON into a list of classes (same rules as the website) |
| `SilentScheduler.java` | Decides "silent right now?", sets the ringer, sets one alarm for the next change |
| `AlarmReceiver.java` | Runs when a class starts or ends |
| `BootReceiver.java` | Puts the timer back after a restart or app update |
| `SettingsActivity.java` | Permissions, options, skip-today, test button, your week |

This repo is separate from the website. The only link between them is the address
`https://srmsync.in` (in `MainActivity.java` and `TimetableSync.java`) and the existing
`/api/timetable` route on the website's server.
