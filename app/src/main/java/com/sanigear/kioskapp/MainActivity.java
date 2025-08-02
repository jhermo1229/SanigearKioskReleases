package com.sanigear.kioskapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout layout;
    private int tapCount = 0;
    private long lastTapTime = 0;

    private static final String TAG = "KioskApp";
    private static final String ALLOWED_DOMAIN = "automation.sanigear.app";
    private boolean kioskModeDisabledByAdmin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");

        layout = new FrameLayout(this);
        setContentView(layout);

        setupDeviceOwnerAndLockTask();

        if (isNetworkAvailable()) {
            showWebView();
        } else {
            showOfflineMessage();
        }

        setupWifiButton();
    }

    private void setupDeviceOwnerAndLockTask() {
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
            if (dpm.isLockTaskPermitted(getPackageName())) {
                try {
                    startLockTask();
                } catch (Exception e) {
                    Log.e(TAG, "startLockTask() failed", e);
                }
            }
        } else {
            Toast.makeText(this, "App is not Device Owner", Toast.LENGTH_LONG).show();
        }
    }

    private void setupWifiButton() {
        Button wifiButton = new Button(this);
        wifiButton.setText("Wi-Fi Settings");
        wifiButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        FrameLayout.LayoutParams wifiParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        wifiParams.gravity = Gravity.BOTTOM | Gravity.END;
        wifiParams.setMargins(30, 30, 30, 30);
        layout.addView(wifiButton, wifiParams);
    }

    private void showWebView() {
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new SecureWebViewClient());
        webView.loadUrl("https://" + ALLOWED_DOMAIN + "/a/login");

        layout.removeAllViews();
        layout.addView(webView);
    }

    private void showOfflineMessage() {
        layout.removeAllViews();

        TextView message = new TextView(this);
        message.setText("No internet connection.\nPlease connect to Wi-Fi.");
        message.setGravity(Gravity.CENTER);
        message.setTextSize(20);

        Button retryButton = new Button(this);
        retryButton.setText("Try Again");
        retryButton.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                showWebView();
            } else {
                Toast.makeText(this, "Still no internet.", Toast.LENGTH_SHORT).show();
            }
        });

        FrameLayout.LayoutParams msgParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        msgParams.gravity = Gravity.CENTER;

        FrameLayout.LayoutParams retryParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        retryParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        retryParams.topMargin = 300;

        layout.addView(message, msgParams);
        layout.addView(retryButton, retryParams);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (kioskModeDisabledByAdmin) return;

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName()) &&
                dpm.isLockTaskPermitted(getPackageName())) {
            try {
                startLockTask();
            } catch (Exception e) {
                Log.e(TAG, "Failed to resume lock task", e);
            }
        }
    }

    private static class SecureWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return uri.getHost() == null || !uri.getHost().contains(ALLOWED_DOMAIN);
        }
    }

    private void showAdminPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Admin PIN");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPin = input.getText().toString();
            if ("1234".equals(enteredPin)) {
                kioskModeDisabledByAdmin = true;
                stopLockTask();
                Toast.makeText(this, "Kiosk Mode Disabled", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTapTime < 2000) {
                tapCount++;
                if (tapCount >= 5) {
                    showAdminPinDialog();
                    tapCount = 0;
                }
            } else {
                tapCount = 1;
            }
            lastTapTime = currentTime;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
