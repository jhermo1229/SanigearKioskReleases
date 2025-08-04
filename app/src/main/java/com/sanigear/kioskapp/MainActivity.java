package com.sanigear.kioskapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.MediaPlayer;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MainActivity for Sanigear Kiosk App
 * Handles WebView display, kiosk mode enforcement, PDF downloads, watchdog logic, and admin unlock
 */
public class MainActivity extends Activity {

    private static final String TAG = "KioskApp";
    private static final String ALLOWED_DOMAIN = "automation.sanigear.app";

    // Layout components
    private FrameLayout layout;
    private LinearLayout toolbar;
    private WebView webView;
    private WebView popupWebView;

    // Admin control
    private boolean kioskModeDisabledByAdmin = false;
    private int tapCount = 0;
    private long lastTapTime = 0;
    private boolean isInPdfViewer = false;

    // Watchdog setup
    private Handler watchdogHandler = new Handler();
    private Runnable watchdogChecker;
    private static final long CHECK_INTERVAL = 3000; // 3 seconds

    private static final Set<String> WHITELISTED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.sanigear.kioskapp",
            "com.adobe.reader",
            "com.android.printspooler",
            "com.android.bips",
            "com.google.android.printservice.recommendation"
    ));

    /**
     * Initializes activity layout and kiosk controls.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        layout = new FrameLayout(this);
        setContentView(layout);

        // Device Owner & Kiosk enforcement
        setupDeviceOwnerAndLockTask();

        // Setup toolbar UI
        setupToolbar();

        // Prompt user if usage access is missing
        if (!hasUsageAccess()) {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Enable Usage Access for kiosk app", Toast.LENGTH_LONG).show();
        }

        // Load main content if online
        if (isNetworkAvailable()) {
            showWebView();
        } else {
            showOfflineMessage();
        }
    }

    // Watchdog logic to detect unauthorized app usage
    private void startWatchdog() {
        watchdogChecker = () -> {
            String currentApp = getForegroundApp();
            Log.d("Watchdog", "Current foreground app: " + currentApp);

            if (currentApp != null && !isAppWhitelisted(currentApp)) {
                Log.w("Watchdog", "Kicking app: " + currentApp);
                recoverToKiosk();
            }

            watchdogHandler.postDelayed(watchdogChecker, CHECK_INTERVAL);
        };
        watchdogHandler.post(watchdogChecker);
    }

    private void stopWatchdog() {
        if (watchdogChecker != null) {
            watchdogHandler.removeCallbacks(watchdogChecker);
        }
    }

    /**
     * Determines which app is currently in the foreground.
     */
    private String getForegroundApp() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 5000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String lastApp = null;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.getPackageName();
            }
        }
        return lastApp;
    }

    /**
     * App whitelist used to allow Adobe Reader and Kiosk app itself.
     */
    private boolean isAppWhitelisted(String packageName) {
        for (String allowed : WHITELISTED_PACKAGES) {
            if (packageName.equals(allowed) || packageName.startsWith(allowed + ".")) {
                return true;
            }
        }
        return false;
    }
    /**
     * Forces the app back to MainActivity (Kiosk Home).
     */
    private void recoverToKiosk() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    /**
     * Determines if Sanigear is currently the default launcher.
     */
    private boolean isDefaultLauncher() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);

        return resolveInfo != null && resolveInfo.activityInfo != null
                && getPackageName().equals(resolveInfo.activityInfo.packageName);
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Prompts user to make kiosk app the default launcher.
     */
    private void ensureDefaultLauncher() {
        if (!isDefaultLauncher()) {
            promptUserToSelectLauncher();
            Toast.makeText(this, "Please select Sanigear Kiosk as the Home app.", Toast.LENGTH_LONG).show();
            openDefaultAppsSettings();
        }
    }

    /**
     * Enables lock task (kiosk) mode if app is device owner.
     */
    private void setupDeviceOwnerAndLockTask() {
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});

            if (dpm.isLockTaskPermitted(getPackageName())) {
                try {
                    startLockTask();
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                    Log.d(TAG, "Kiosk Mode started");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start lock task", e);
                }
            }
        } else {
            Toast.makeText(this, "App is not Device Owner", Toast.LENGTH_LONG).show();
        }
    }
    // Kiosk Toolbar Setup
    private void setupToolbar() {
        if (toolbar != null && toolbar.getParent() != null)
            ((ViewGroup) toolbar.getParent()).removeView(toolbar);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(10, 10, 10, 10);

        // Add essential toolbar buttons
        addButton("Back", v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });
        addButton("Refresh", v -> {
            if (isNetworkAvailable()) showWebView();
            else Toast.makeText(this, "Still no internet.", Toast.LENGTH_SHORT).show();
        });
        addButton("Wi-Fi", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addButton("*", v -> showAboutDialog());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.setMargins(30, 30, 30, 30);
        layout.addView(toolbar, params);
    }

    // Reusable button creator for the toolbar
    private void addButton(String label, View.OnClickListener action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(action);
        toolbar.addView(btn);
    }

    // Initialize main WebView and support for popup windows
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

    // Handle PDF download and launch
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

                deleteExistingPdfs();
                pdfFile = File.createTempFile("temp_", ".pdf", getCacheDir());
                Log.d("PDFHandlerDelete", "Temp PDF created at: " + pdfFile.getAbsolutePath());

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
                startService(new Intent(this, AppWatchdogService.class));
                startActivity(intent);

            } catch (Exception e) {
                Log.e("PDFHandler", "Failed to open PDF", e);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                if (pdfFile != null) pdfFile.delete();
            }
        }).start();
    }

    // Removes previously downloaded PDFs
    private void deleteExistingPdfs() {
        File cacheDir = getCacheDir();
        if (cacheDir == null) {
            Log.d("PDFHandlerDelete", "Cache dir is null.");
            return;
        }

        Log.d("PDFHandlerDelete", "Cache path: " + cacheDir.getAbsolutePath());

        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            Log.d("PDFHandlerDelete", "No files found in cache.");
            return;
        }

        for (File f : files) {
            Log.d("PDFHandlerDelete", "Found file: " + f.getName());
            if (f.getName().endsWith(".pdf")) {
                Log.d("PDFHandlerDelete", "Deleting file: " + f.getName());
                boolean deleted = f.delete();
                Log.d("PDFHandlerDelete", "Deleted: " + deleted);
            }
        }
    }

    // Shows offline message if Wi-Fi or data is unavailable
    private void showOfflineMessage() {
        layout.removeAllViews();
        TextView msg = new TextView(this);
        msg.setText("No internet connection. \nPlease connect to Wi-Fi.");
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(20);
        layout.addView(msg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setupToolbar();
    }

    // Check if network connection is active
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }
    private void promptUserToSelectLauncher() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openDefaultAppsSettings() {
        Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean isInKioskMode() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } else {
            // API < 23 (pre-Marshmallow)
            //noinspection deprecation
            return am.isInLockTaskMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity resumed");
        ensureDefaultLauncher();

        Log.d("MainActivity", "Resuming, ensuring watchdog is running");

        Intent watchdog = new Intent(this, AppWatchdogService.class);
        startService(watchdog);

        if (!isInKioskMode()) {
            Log.d("MainActivity", "Not in Kiosk Mode. Starting watchdog.");
            startWatchdog();
        } else {
            Log.d("MainActivity", "In Kiosk Mode. Stopping watchdog.");
            stopWatchdog();
        }
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

        Utils.checkForUpdate(this);
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

                // ✅ Stop watchdog service
                stopService(new Intent(this, AppWatchdogService.class));

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

        String versionName = "Unknown";
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        TextView info = new TextView(this);
        info.setText("Sanigear Kiosk v" + versionName +
                "\nDeveloped by Jeff Hermo " +
                "\nContact: jeff@sanigear.ca\n\n");

        info.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(info);

        Button jumpBtn = new Button(this);
        jumpBtn.setText("Don't push 5x");
        jumpBtn.setBackgroundColor(Color.RED);
        final int[] tapCounter = {0};
        final MediaPlayer[] meowSound = {MediaPlayer.create(this, R.raw.meow)};

        jumpBtn.setOnClickListener(v -> {
            tapCounter[0]++;

            TranslateAnimation jump = new TranslateAnimation(0, 0, 0, -150);
            jump.setDuration(300);
            jump.setRepeatMode(Animation.REVERSE);
            jump.setRepeatCount(1);
            cat.startAnimation(jump);

            if (tapCounter[0] == 5) {
                if (meowSound[0] != null) {
                    meowSound[0].start();
                }
                Toast.makeText(this, "Meow! 🐱", Toast.LENGTH_SHORT).show();
                tapCounter[0] = 0;
            }
        });

        container.addView(jumpBtn);

        Button updateBtn = new Button(this);
        updateBtn.setText("Check for Update");
        updateBtn.setBackgroundColor(Color.GREEN);
        updateBtn.setOnClickListener(v -> {
            updateBtn.setEnabled(false);
            updateBtn.setText("Checking...");

            Utils.checkForUpdate(this, new Utils.UpdateListener() {
                @Override
                public void onUpToDate() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "You're already up to date!", Toast.LENGTH_SHORT).show();
                        updateBtn.setEnabled(true);
                        updateBtn.setText("Check for Update");
                    });
                }

                @Override
                public void onUpdateAvailable() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Downloading update...", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Update check failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        updateBtn.setEnabled(true);
                        updateBtn.setText("Check for Update");
                    });
                }
            });
        });
        container.addView(updateBtn);


        builder.setView(container);

        TextView watchdogStatus = new TextView(this);
        watchdogStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(watchdogStatus);

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
