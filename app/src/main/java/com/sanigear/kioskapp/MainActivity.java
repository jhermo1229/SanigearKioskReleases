package com.sanigear.kioskapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private LinearLayout toolbar; // Move this to be a class-level field if not already
    private WebView popupWebView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        layout = new FrameLayout(this);
        setContentView(layout);

        setupDeviceOwnerAndLockTask();

        if (isNetworkAvailable()) {
            showWebView();
        } else {
            showOfflineMessage();
        }

        setupToolbar(); // Adds Back, Refresh, Wi-Fi buttons
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

    private void setupToolbar() {

        // Remove existing toolbar if already added
        if (toolbar != null && toolbar.getParent() != null) {
            ((ViewGroup) toolbar.getParent()).removeView(toolbar);
        }

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(10, 10, 10, 10);

        Button backBtn = new Button(this);
        backBtn.setText("Back");
        backBtn.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
        });

        Button refreshBtn = new Button(this);
        refreshBtn.setText("Refresh");
        refreshBtn.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                showWebView();
            } else {
                Toast.makeText(this, "Still no internet.", Toast.LENGTH_SHORT).show();
            }
        });

        Button wifiBtn = new Button(this);
        wifiBtn.setText("Wi-Fi");
        wifiBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        Button aboutButton = new Button(this);
        aboutButton.setText("*");
        aboutButton.setOnClickListener(v -> showAboutDialog());

        toolbar.addView(backBtn);
        toolbar.addView(refreshBtn);
        toolbar.addView(wifiBtn);
        toolbar.addView(aboutButton);

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        toolbarParams.gravity = Gravity.TOP | Gravity.START;
        toolbarParams.setMargins(30, 30, 30, 30);

        layout.addView(toolbar, toolbarParams);
    }

    private void showWebView() {
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.getSettings().setSupportMultipleWindows(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView newWebView = new WebView(MainActivity.this);
                newWebView.getSettings().setJavaScriptEnabled(true);
                newWebView.getSettings().setDomStorageEnabled(true);

                newWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        Uri url = request.getUrl();
                        String urlStr = url.toString();

                        if (urlStr.endsWith(".pdf")) {
                            String googleViewer = "https://docs.google.com/gview?embedded=true&url=" + Uri.encode(urlStr);
                            view.loadUrl(googleViewer);
                            return true;
                        }

                        return false;
                    }
                });

                popupWebView = newWebView;
                layout.addView(newWebView);

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();

                return true;
            }
        });

        webView.setWebViewClient(new SecureWebViewClient());

        layout.removeAllViews();
        layout.addView(webView);
        webView.loadUrl("https://" + ALLOWED_DOMAIN + "/a/login");

        // Re-add toolbar after clearing views
        setupToolbar();
    }

    private void showOfflineMessage() {
        layout.removeAllViews();

        TextView message = new TextView(this);
        message.setText("No internet connection.\nPlease connect to Wi-Fi.");
        message.setGravity(Gravity.CENTER);
        message.setTextSize(20);

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

        setupToolbar(); // Include toolbar even in offline state
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
            String url = uri.toString();

            if (url.endsWith(".pdf")) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/pdf");
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    view.getContext().startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(view.getContext(), "No PDF viewer found", Toast.LENGTH_LONG).show();
                }
                return true;
            }

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

    private void showAboutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        TextView title = new TextView(this);
        title.setText("About the Developer");
        title.setPadding(20, 30, 20, 10);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        builder.setCustomTitle(title);

        // Container layout
        LinearLayout container = new LinearLayout(MainActivity.this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 50, 50, 50);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        // Cat Image
        ImageView cat = new ImageView(MainActivity.this);
        cat.setImageResource(R.drawable.cat_sprite); // ← Replace with your cat image filename
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(300, 300);
        catParams.bottomMargin = 20;
        cat.setLayoutParams(catParams);
        container.addView(cat);

        // Info Text
        TextView info = new TextView(MainActivity.this);
        info.setText("Kiosk App v1.0\n\nDeveloped by Jeff Hermo\nContact: jeff@sanigear.ca");
        info.setTextSize(16);
        info.setPadding(0, 20, 0, 30);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(info);

        // Jump Button
        Button jumpBtn = new Button(MainActivity.this);
        jumpBtn.setBackgroundColor(Color.RED);
        jumpBtn.setText("Self Destruct Button");
        jumpBtn.setOnClickListener(v -> {
            TranslateAnimation jump = new TranslateAnimation(0, 0, 0, -150);
            jump.setDuration(300);
            jump.setRepeatMode(Animation.REVERSE);
            jump.setRepeatCount(1);
            cat.startAnimation(jump);
        });

        container.addView(jumpBtn);

        builder.setView(container);
        builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }





}
