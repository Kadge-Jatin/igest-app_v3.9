package com.example.tremor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class TremorBootService extends Service {
    private static final String TAG = "TremorBootService";
    private static final String CHANNEL_ID = "tremor_boot_channel";
    private static final int NOTIF_ID = 4242;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate — creating notification channel and attempting to start foreground");
        createNotificationChannel();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tremor")
                .setContentText("Tremor background service is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // Start foreground with type on Android S+; fallback to two-arg startForeground otherwise.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Use the types that match your manifest attribute (connectedDevice|location)
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                startForeground(NOTIF_ID, notif, types);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (SecurityException se) {
            Log.e(TAG, "startForeground SecurityException — check FOREGROUND_SERVICE permission and merged manifest", se);
            stopSelf();
            return;
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            stopSelf();
            return;
        }

        // Initialize Tremor library safely (idempotent)
        try {
            TremorInitializer.init(getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "TremorInitializer failed during service start", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Tremor Service", NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("Notifications for Tremor background service");
            NotificationManager mgr = (NotificationManager) getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(chan);
        }
    }
}