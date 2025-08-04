package com.sanigear.kioskapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class Utils {

    public interface UpdateListener {
        void onUpToDate();
        void onUpdateAvailable();
        void onError(Exception e);
    }

    public static void checkForUpdate(Context context) {
        checkForUpdate(context, null);
    }

    public static void checkForUpdate(Context context, @Nullable UpdateListener listener) {
        SharedPreferences prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("first_launch_done", true).apply();
        long lastCheck = prefs.getLong("last_check", 0);
        long now = System.currentTimeMillis();

        // Only auto-check once a week (unless user manually triggers)
        if (listener == null && now - lastCheck < 7 * 24 * 60 * 60 * 1000) return;

        new Thread(() -> {
            try {
                Log.d("UpdateUtils", "Checking GitHub for latest release...");
                URL url = new URL("https://api.github.com/repos/jhermo1229/SanigearKioskReleases/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (conn.getResponseCode() != 200)
                    throw new IOException("GitHub API error " + conn.getResponseCode());

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String latestTag = json.getString("tag_name")
                        .replace("SanigearKiosk_v", "")  // Strip the tag prefix
                        .replace("v", "");               // Just in case

                JSONArray assets = json.getJSONArray("assets");
                if (assets.length() == 0)
                    throw new IOException("No APK asset found in latest GitHub release");

                String apkUrl = assets.getJSONObject(0).getString("browser_download_url");

                String currentVersion = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;

                Log.d("UpdateUtils", "Latest tag: " + latestTag + " | Current: " + currentVersion);

                if (isNewerVersion(latestTag, currentVersion)) {
                    Log.d("UpdateUtils", "Newer version found, downloading...");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context, "Update available — downloading...", Toast.LENGTH_LONG).show();
                        if (listener != null) listener.onUpdateAvailable();
                    });
                    downloadAndInstall(context, apkUrl);
                } else {
                    Log.d("UpdateUtils", "App is already up to date.");
                    if (listener != null) {
                        new Handler(Looper.getMainLooper()).post(listener::onUpToDate);
                    }
                }

                prefs.edit().putLong("last_check", now).apply();

            } catch (Exception e) {
                Log.e("UpdateUtils", "Update check failed", e);
                if (listener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            listener.onError(e)
                    );
                }
            }
        }).start();

    }

    private static boolean isNewerVersion(String newVer, String oldVer) {
        // Naive string compare assumes "1.2" > "1.1"
        return newVer.compareTo(oldVer) > 0;
    }

    private static void downloadAndInstall(Context context, String apkUrl) throws IOException {
        Log.d("UpdateUtils", "Starting download from: " + apkUrl);

        File apkFile = File.createTempFile("update", ".apk", context.getCacheDir());
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.connect();

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download APK. HTTP " + conn.getResponseCode());
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(apkFile)) {

            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }

        Log.d("UpdateUtils", "APK saved to: " + apkFile.getAbsolutePath());

        Uri apkUri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);

        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Download complete — starting install", Toast.LENGTH_LONG).show()
        );

    }
}
