package com.srmsync.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarms are wiped when the phone restarts, the app updates, or the clock
 * changes. This puts the class timer back.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SilentScheduler.refresh(context);
    }
}
