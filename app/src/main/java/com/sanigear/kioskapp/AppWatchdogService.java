package com.sanigear.kioskapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AppWatchdogService extends Service {

    private static final String TAG = "AppWatchdogService";
    private static final long INTERVAL = 3000;

    private static final String KIOSK_PACKAGE = "com.sanigear.kioskapp";
    private static final String[] WHITELIST = {
            KIOSK_PACKAGE,
            "com.adobe.reader"
    };

    private Handler handler;
    private Runnable checker;

    @Override
    public void onCreate() {
        super.onCreate();

        // ⏳ Start foreground service on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, createNotification());
        }

        handler = new Handler();
        checker = () -> {
            String currentApp = getForegroundApp();

            if (currentApp != null) {
                if (KIOSK_PACKAGE.equals(currentApp)) {
                    Log.d(TAG, "Kiosk app resumed. Stopping watchdog.");
                    stopSelf(); // ✅ Stop watchdog when kiosk is active
                    return;
                }

                if (!isWhitelisted(currentApp)) {
                    Log.w(TAG, "Unauthorized app: " + currentApp);
                    recoverToKiosk();
                }
            }

            handler.postDelayed(checker, INTERVAL);
        };


        handler.post(checker);
    }

    private boolean isWhitelisted(String packageName) {
        for (String allowed : WHITELIST) {
            if (allowed.equals(packageName)) return true;
        }
        return false;
    }

    private void recoverToKiosk() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private String getForegroundApp() {
        Log.d(TAG, "Checking usage events...");
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 10000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String lastApp = null;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            Log.d(TAG, "Eeevent: " + event.getPackageName() + " Type: " + event.getEventType());
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.getPackageName();
            }
        }
        return lastApp;
    }

    private Notification createNotification() {
        String channelId = "watchdog_channel";
        String channelName = "Kiosk App Watchdog";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(channelId, channelName,
                    NotificationManager.IMPORTANCE_MIN);
            chan.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(chan);
        }

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Kiosk Mode Watchdog")
                .setContentText("Monitoring apps...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, "watchdog_channel")
                .setContentTitle("Kiosk Watchdog")
                .setContentText("Monitoring device...")
                .setSmallIcon(R.drawable.ic_launcher)
                .build();

        startForeground(1, notification);

        Log.d(TAG, "AppWatchdogService started");
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        handler.removeCallbacks(checker);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
