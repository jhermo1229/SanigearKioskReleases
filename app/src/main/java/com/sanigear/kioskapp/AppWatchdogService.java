package com.sanigear.kioskapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AppWatchdogService extends Service {

    private static final String TAG = "AppWatchdogService";
    private static final long INTERVAL = 2000; // Run every 2 seconds
    private static final String CHANNEL_ID = "watchdog_channel";

    private static final String KIOSK_PACKAGE = "com.sanigear.kioskapp";
    private static final Set<String> WHITELISTED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.sanigear.kioskapp",
            "com.adobe.reader",
            "com.android.printspooler",
            "com.android.bips",
            "com.google.android.printservice.recommendation",
            "com.android.settings"
    ));

    private Handler handler;
    private Runnable checker;
    private String lastApp = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification());

        Toast.makeText(this, "Watchdog started", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Watchdog started");

        handler = new Handler();
        checker = new Runnable() {
            @Override
            public void run() {
                String currentApp = getForegroundApp();
                if (currentApp != null && !isAppWhitelisted(currentApp)) {
                    if (!currentApp.equals(lastApp)) {
                        Log.w(TAG, "Unauthorized app: " + currentApp);
                        recoverToKiosk();
                        lastApp = currentApp;
                    }
                } else {
                    lastApp = currentApp;
                }

                handler.postDelayed(this, INTERVAL);
            }
        };
        handler.post(checker);
    }

    private boolean isAppWhitelisted(String packageName) {
        for (String allowed : WHITELISTED_PACKAGES) {
            if (packageName.equals(allowed) || packageName.startsWith(allowed + ".")) {
                return true;
            }
        }
        return false;
    }
    private void recoverToKiosk() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private String getForegroundApp() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 10000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String lastAppSeen = null;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastAppSeen = event.getPackageName();
            }
        }

        Log.d(TAG, "Foreground app: " + lastAppSeen);
        return lastAppSeen;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "Kiosk App Watchdog",
                    NotificationManager.IMPORTANCE_MIN
            );
            chan.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(chan);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Watchdog Running")
                .setContentText("Monitoring active apps...")
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checker);
        super.onDestroy();
        Log.d(TAG, "Watchdog stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
