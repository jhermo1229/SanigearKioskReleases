package com.sanigear.kioskapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public class AppUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AppUpdateReceiver", "App was updated via MY_PACKAGE_REPLACED");


        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Install complete. App will restart automatically.", Toast.LENGTH_LONG).show()
        );

        // Restart the app
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
    }
}
