package com.sanigear.kioskapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("KioskApp", "BootReceiver triggered: " + intent.getAction());

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, AppWatchdogService.class));
            Log.d("KioskApp", "Boot or Locked Boot completed. Launching MainActivity...");

            Intent launchIntent = new Intent(context, SplashActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            context.startActivity(launchIntent);
        }
    }
}
