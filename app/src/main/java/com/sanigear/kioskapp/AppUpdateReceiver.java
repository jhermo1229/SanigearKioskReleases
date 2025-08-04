package com.sanigear.kioskapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import android.app.admin.DevicePolicyManager;
import android.content.pm.PackageManager;

public class AppUpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AppUpdateReceiver", "App was updated via MY_PACKAGE_REPLACED");

        // Show a toast to notify the user about the update
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Install complete. App will restart automatically.", Toast.LENGTH_LONG).show()
        );

        // Set Sanigear Kiosk as the default home app
        setSanigearAsHomeApp(context);

        // Restart the app
        Intent restartIntent = new Intent(context, MainActivity.class);
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(restartIntent);
    }

    // Method to set Sanigear Kiosk as the default home app
    private void setSanigearAsHomeApp(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            // Check if the app is the Device Owner
            if (dpm.isDeviceOwnerApp(context.getPackageName())) {
                ComponentName adminComponent = new ComponentName(context, MyDeviceAdminReceiver.class);

                // Set the package as the home app
                dpm.setLockTaskPackages(adminComponent, new String[]{context.getPackageName()});

                // Optionally, if you want to explicitly set this as the home launcher, use the following
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                ComponentName componentName = new ComponentName("com.sanigear.kioskapp", "com.sanigear.kioskapp.MainActivity");
                intent.setComponent(componentName);
                context.startActivity(intent);  // Start home activity
            }
        }
    }
}
