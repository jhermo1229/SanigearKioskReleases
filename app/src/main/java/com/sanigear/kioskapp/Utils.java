package com.sanigear.kioskapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
        long lastCheck = prefs.getLong("last_check", 0);
        long now = System.currentTimeMillis();

        // Skip if too soon and no user override
        if (listener == null && now - lastCheck < 7 * 24 * 60 * 60 * 1000) return;

        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/jhermo1229/SanigearKioskReleases/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (conn.getResponseCode() != 200)
                    throw new IOException("HTTP error " + conn.getResponseCode());

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String latestTag = json.getString("tag_name").replace("v", "");
                JSONArray assets = json.getJSONArray("assets");
                if (assets.length() == 0) throw new IOException("No APK found in latest release");

                String apkUrl = assets.getJSONObject(0).getString("browser_download_url");

                String currentVersion = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;

                if (isNewerVersion(latestTag, currentVersion)) {
                    Log.d("UpdateUtils", "Update available");
                    if (listener != null) {
                        new Handler(Looper.getMainLooper()).post(listener::onUpdateAvailable);
                    }
                    downloadAndInstall(context, apkUrl);
                } else {
                    if (listener != null) {
                        new Handler(Looper.getMainLooper()).post(listener::onUpToDate);
                    }
                }

                prefs.edit().putLong("last_check", now).apply();

            } catch (Exception e) {
                Log.e("UpdateUtils", "Error", e);
                if (listener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> listener.onError(e));
                }
            }
        }).start();
    }

    private static boolean isNewerVersion(String newVer, String oldVer) {
        return newVer.compareTo(oldVer) > 0;
    }

    private static void downloadAndInstall(Context context, String apkUrl) throws IOException {
        File apkFile = File.createTempFile("update", ".apk", context.getCacheDir());
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.connect();

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(apkFile)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }

        Uri apkUri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}