
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
import android.os.Message;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.webkit.CookieManager;
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

import androidx.core.content.FileProvider;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String TAG = "KioskApp";
    private static final String ALLOWED_DOMAIN = "automation.sanigear.app";

    private FrameLayout layout;
    private LinearLayout toolbar;
    private WebView webView;
    private WebView popupWebView;
    private boolean kioskModeDisabledByAdmin = false;
    private int tapCount = 0;
    private long lastTapTime = 0;

    private boolean isInPdfViewer = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        layout = new FrameLayout(this);
        setContentView(layout);

        setupDeviceOwnerAndLockTask();
        setupToolbar();

        if (isNetworkAvailable()) {
            showWebView();
        } else {
            showOfflineMessage();
        }
    }

    private void setupDeviceOwnerAndLockTask() {
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            // Whitelist only your app
            dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});

            if (dpm.isLockTaskPermitted(getPackageName())) {
                try {
                    // Full immersive kiosk mode (no status bar, nav bar, recents)
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );

                    startLockTask(); // Lock task now enforces full lock
                    Log.d(TAG, "Kiosk Mode started with Lock Task");
                } catch (Exception e) {
                    Log.e(TAG, "startLockTask() failed", e);
                }
            }
        } else {
            Toast.makeText(this, "App is not Device Owner", Toast.LENGTH_LONG).show();
        }
    }


    private void setupToolbar() {
        if (toolbar != null && toolbar.getParent() != null)
            ((ViewGroup) toolbar.getParent()).removeView(toolbar);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(10, 10, 10, 10);

        addButton("Back", v -> { if (webView != null && webView.canGoBack()) webView.goBack(); });
        addButton("Refresh", v -> { if (isNetworkAvailable()) showWebView(); else Toast.makeText(this, "Still no internet.", Toast.LENGTH_SHORT).show(); });
        addButton("Wi-Fi", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addButton("*", v -> showAboutDialog());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setMargins(30, 30, 30, 30);
        layout.addView(toolbar, params);
    }

    private void addButton(String label, View.OnClickListener action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(action);
        toolbar.addView(btn);
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
                        String url = request.getUrl().toString();
                        if (url.endsWith(".pdf")) {
                            downloadAndOpenPDF(url);
                            return true;
                        }
                        return false;
                    }
                });
                popupWebView = newWebView;
                layout.addView(popupWebView);
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
        setupToolbar();
    }

    private void downloadAndOpenPDF(String urlStr) {
        new Thread(() -> {
            File pdfFile = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                String cookie = CookieManager.getInstance().getCookie(urlStr);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new IOException("HTTP error: " + conn.getResponseCode());

                pdfFile = File.createTempFile("temp_", ".pdf", getCacheDir());
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     OutputStream out = new FileOutputStream(pdfFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }

                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/pdf");
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                stopLockTask();
                isInPdfViewer = true;
                startActivity(intent);

                File finalPdfFile = pdfFile;
                runOnUiThread(() -> new Thread(() -> {
                    try { Thread.sleep(10000); finalPdfFile.delete(); } catch (Exception ignored) {}
                }).start());

            } catch (Exception e) {
                Log.e("PDFHandler", "Failed to open PDF", e);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                if (pdfFile != null) pdfFile.delete();
            }
        }).start();
    }

    private void showOfflineMessage() {
        layout.removeAllViews();
        TextView msg = new TextView(this);
        msg.setText("No internet connection. " +
                "\nPlease connect to Wi-Fi.");
                msg.setGravity(Gravity.CENTER);
        msg.setTextSize(20);
        layout.addView(msg, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setupToolbar();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity resumed");

        // Restart lock task if needed
        if (!kioskModeDisabledByAdmin) {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName()) &&
                    dpm.isLockTaskPermitted(getPackageName())) {
                try {
                    Log.d(TAG, "Re-entering Lock Task Mode");
                    startLockTask();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to resume lock task", e);
                }
            }
        }

        // ✅ Reattach WebView if not present
        if (webView != null) {
            layout.removeAllViews(); // ensure clean
            layout.addView(webView);
            setupToolbar();

            webView.setVisibility(View.VISIBLE);
            webView.reload(); // refresh page if needed
        }
    }




    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < 2000) tapCount++; else tapCount = 1;
            if (tapCount >= 5) { showAdminPinDialog(); tapCount = 0; }
            lastTapTime = now;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showAdminPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Admin PIN");
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);
        builder.setPositiveButton("Unlock", (dialog, which) -> {
            if ("1234".equals(input.getText().toString())) {
                kioskModeDisabledByAdmin = true;
                stopLockTask();
                Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                finish();
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showAboutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        TextView title = new TextView(this);
        title.setText("About the Developer");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(20);
        builder.setCustomTitle(title);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(50, 50, 50, 50);

        ImageView cat = new ImageView(this);
        cat.setImageResource(R.drawable.cat_sprite);
        cat.setLayoutParams(new LinearLayout.LayoutParams(300, 300));
        container.addView(cat);

        TextView info = new TextView(this);
        info.setText("Kiosk App v1.0 " +
                        "\nDeveloped by Jeff Hermo " +
                "\n\nContact: jeff@sanigear.ca");
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(info);

        Button jumpBtn = new Button(this);
        jumpBtn.setText("Self Destruct Button");
        jumpBtn.setBackgroundColor(Color.RED);
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
        builder.show();
    }

    private static class SecureWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String url = uri.toString();
            if (url.endsWith(".pdf")) return true;
            return uri.getHost() == null || !uri.getHost().contains(ALLOWED_DOMAIN);
        }
    }
}
