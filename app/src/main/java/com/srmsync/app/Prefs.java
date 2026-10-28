package com.srmsync.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the app remembers, in one small settings file on the phone. */
final class Prefs {

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("srmsync", Context.MODE_PRIVATE);
    }

    // ---- user options ----
    boolean enabled() { return sp.getBoolean("enabled", true); }
    void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    boolean vibrateMode() { return sp.getBoolean("vibrate", false); }
    void setVibrateMode(boolean v) { sp.edit().putBoolean("vibrate", v).apply(); }

    boolean mergeBreaks() { return sp.getBoolean("mergeBreaks", true); }
    void setMergeBreaks(boolean v) { sp.edit().putBoolean("mergeBreaks", v).apply(); }

    String skipDate() { return sp.getString("skipDate", ""); }
    void setSkipDate(String v) { sp.edit().putString("skipDate", v).commit(); }

    long testUntil() { return sp.getLong("testUntil", 0L); }
    void setTestUntil(long v) { sp.edit().putLong("testUntil", v).commit(); }

    boolean onboarded() { return sp.getBoolean("onboarded", false); }
    void setOnboarded() { sp.edit().putBoolean("onboarded", true).apply(); }

    // ---- timetable ----
    String blocksJson() { return sp.getString("blocks", "[]"); }
    long lastSyncAt() { return sp.getLong("lastSyncAt", 0L); }
    String lastSyncSession() { return sp.getString("lastSyncSession", ""); }

    void saveTimetable(String blocksJson, String sessionId, long at) {
        sp.edit()
          .putString("blocks", blocksJson)
          .putString("lastSyncSession", sessionId)
          .putLong("lastSyncAt", at)
          .commit();
    }

    long lastAttemptAt() { return sp.getLong("lastAttemptAt", 0L); }
    void setLastAttemptAt(long v) { sp.edit().putLong("lastAttemptAt", v).apply(); }

    /** The website's login session id, as last seen in the WebView. */
    String lastSessionSeen() { return sp.getString("lastSessionSeen", ""); }
    void setLastSessionSeen(String v) { sp.edit().putString("lastSessionSeen", v).apply(); }

    // ---- ringer bookkeeping ----
    boolean appSilenced() { return sp.getBoolean("appSilenced", false); }
    int prevRinger() { return sp.getInt("prevRinger", -1); }
    int setRinger() { return sp.getInt("setRinger", -1); }

    void markSilenced(int previousMode, int modeWeSet) {
        sp.edit()
          .putBoolean("appSilenced", true)
          .putInt("prevRinger", previousMode)
          .putInt("setRinger", modeWeSet)
          .commit();
    }

    void clearSilenced() {
        sp.edit().putBoolean("appSilenced", false).commit();
    }
}
