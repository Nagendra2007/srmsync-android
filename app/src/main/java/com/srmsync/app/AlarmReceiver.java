package com.srmsync.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when a class starts or ends. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SilentScheduler.refresh(context);
    }
}
