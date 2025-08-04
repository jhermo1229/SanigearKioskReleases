package com.sanigear.kioskapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SplashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Logo Image
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);  // logo.png must be in res/drawable/
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Company Name Text
        TextView title = new TextView(this);
        title.setText("SANIGEAR TECHNOLOGIES");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);

        // Fake loading animation (Progress Bar)
        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);

        // Layout setup
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);

        layout.addView(logo);
        layout.addView(title);

        // Add spacing
        TextView spacer = new TextView(this);
        spacer.setText("\n\n");
        layout.addView(spacer);

        layout.addView(progressBar);

        setContentView(layout);

        // Wait 2.5 seconds before launching MainActivity
        new Handler().postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, 2500);

        SharedPreferences prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE);
        boolean firstLaunchDone = prefs.getBoolean("first_launch_done", false);

        if (!firstLaunchDone) {
            Log.d("Splash", "First launch: delaying watchdog 15 seconds");
            new Handler().postDelayed(() -> {
                startWatchdogService();
            }, 15000); // 15 seconds
        } else {
            startWatchdogService();
        }
    }

    private void startWatchdogService() {
        Intent serviceIntent = new Intent(this, AppWatchdogService.class);
        startService(serviceIntent);
    }
}
