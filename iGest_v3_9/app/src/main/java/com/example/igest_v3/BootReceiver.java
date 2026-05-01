package com.example.igest_v3;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "BOOT_COMPLETED received — starting Tremor service");

            // Start Tremor foreground service (service implemented in Tremor module)
            Intent svc = new Intent();
            svc.setClassName(context.getPackageName(), "com.example.tremor.TremorBootService");
            svc.setAction("com.example.tremor.ACTION_START_ON_BOOT");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }
}