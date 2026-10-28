package com.srmsync.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Handles the "End early" button on the silent notification. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Notifier.ACTION_END_EARLY.equals(intent.getAction())) return;
        Prefs p = new Prefs(context);
        long now = System.currentTimeMillis();

        // Stop the test, and skip the rest of the current silent period.
        p.setTestUntil(0);
        List<SilentScheduler.Interval> list = SilentScheduler.upcoming(p, now);
        SilentScheduler.Interval cur = SilentScheduler.current(list, now);
        if (cur != null) p.setSuppressUntil(cur.end);

        SilentScheduler.refresh(context);
    }
}
