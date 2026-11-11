package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WallpaperSyncManager {
    private static final String TAG = "TvHome.WallpaperSync";

    public static final String PRIMARY_MANIFEST_URL =
            "https://raw.githubusercontent.com/Hafiz0ff/SmartOs-TV/main/wallpapers/manifest.json";
    public static final String FALLBACK_MANIFEST_URL =
            "https://hafiz0ff.github.io/SmartOs-TV/wallpapers/manifest.json";

    public static final String PREF_KEY_LAST_SYNC = "wallpaper_last_sync_time";
    public static final String PREF_KEY_SYNC_DAYS = "wallpaper_sync_days";
    public static final String PREF_KEY_UPDATED_AT = "wallpaper_manifest_updated_at";

    private static final int TIMEOUT_MS = 15_000;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean isSyncing = new AtomicBoolean(false);

    public interface SyncCallback {
        void onSyncStarted();
        void onSyncSuccess(int count, boolean updated);
        void onSyncError(String error);
    }

    private WallpaperSyncManager() {}

    /**
     * Checks if sync is due according to the configured interval (7, 10, 14 days)
     * and executes in background if needed.
     */
    public static void checkAndSync(Context context, SyncCallback callback) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        int days = sp.getInt(PREF_KEY_SYNC_DAYS, 7);
        if (days <= 0) {
            Log.d(TAG, "Wallpaper auto-sync is disabled.");
            return;
        }

        long lastSync = sp.getLong(PREF_KEY_LAST_SYNC, 0L);
        long now = System.currentTimeMillis();
        long intervalMs = days * 24L * 3600_000L;

        if (lastSync > 0 && now >= lastSync && (now - lastSync) < intervalMs) {
            Log.d(TAG, "Wallpaper sync not due yet.");
            return;
        }

        syncNow(context, false, callback);
    }

    /**
     * Triggers synchronization immediately.
     */
    public static void syncNow(Context context, boolean force, SyncCallback callback) {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(TAG, "Sync already in progress, skipping.");
            if (callback != null) {
                mainHandler.post(() -> callback.onSyncError("Синхронизация уже выполняется"));
            }
            return;
        }

        if (callback != null) {
            mainHandler.post(callback::onSyncStarted);
        }

        executor.execute(() -> {
            try {
                performSync(context.getApplicationContext(), force, callback);
            } catch (Exception e) {
                Log.e(TAG, "Wallpaper sync failed", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSyncError(e.getMessage() != null ? e.getMessage() : "Ошибка загрузки"));
                }
            } finally {
                isSyncing.set(false);
            }
        });
    }

    private static void performSync(Context context, boolean force, SyncCallback callback) throws Exception {
        String manifestJson = null;
        try {
            manifestJson = fetchString(PRIMARY_MANIFEST_URL);
        } catch (Exception e) {
            Log.w(TAG, "Primary URL failed, trying fallback...", e);
            manifestJson = fetchString(FALLBACK_MANIFEST_URL);
        }

        if (manifestJson == null || manifestJson.trim().isEmpty()) {
            throw new IOException("Пустой ответ от сервера манифеста");
        }

        JSONObject manifest = new JSONObject(manifestJson);
        String updatedAt = manifest.optString("updated_at", "");
        JSONArray imagesArray = manifest.optJSONArray("images");

        if (imagesArray == null || imagesArray.length() == 0) {
            throw new IOException("Манифест не содержит изображений");
        }

        SharedPreferences sp = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String savedUpdatedAt = sp.getString(PREF_KEY_UPDATED_AT, "");

        if (!force && updatedAt.equals(savedUpdatedAt)) {
            Log.i(TAG, "Wallpapers up-to-date (" + updatedAt + ").");
            sp.edit().putLong(PREF_KEY_LAST_SYNC, System.currentTimeMillis()).apply();
            if (callback != null) {
                mainHandler.post(() -> callback.onSyncSuccess(imagesArray.length(), false));
            }
            return;
        }

        File baseDir = Config.dir(context);
        if (baseDir == null) {
            throw new IOException("Внешнее хранилище недоступно");
        }

        File stagingDir = new File(baseDir, "wallpapers.staging");
        deleteRecursively(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IOException("Не удалось создать временную директорию: " + stagingDir.getAbsolutePath());
        }

        StringBuilder captionsText = new StringBuilder();
        JSONArray creditsArray = new JSONArray();

        int downloadedCount = 0;
        for (int i = 0; i < imagesArray.length(); i++) {
            JSONObject img = imagesArray.getJSONObject(i);
            String fileName = img.getString("file");
            String url = img.getString("url");
            String sha256 = img.optString("sha256", "");
            String title = img.optString("title", "");
            String author = img.optString("author", "");
            String license = img.optString("license", "");

            File destFile = new File(stagingDir, fileName);
            downloadFile(url, destFile, sha256);

            // Verify decodability and dimensions
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(destFile.getAbsolutePath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                destFile.delete();
                throw new IOException("Файл поврежден (decode failed): " + fileName);
            }
            if (!WallpaperSize.accepted(opts.outWidth, opts.outHeight)) {
                destFile.delete();
                throw new IOException("Размер " + opts.outWidth + "x" + opts.outHeight + " превышает лимит: " + fileName);
            }

            // Append to captions.txt: filename \t Title - Author, License
            String captionLine = title;
            if (!author.isEmpty() && !title.contains(author)) {
                captionLine += " — " + author;
            }
            if (!license.isEmpty()) {
                captionLine += " (" + license + ")";
            }
            captionsText.append(fileName).append('\t').append(captionLine).append('\n');

            // Append to credits
            JSONObject creditObj = new JSONObject();
            creditObj.put("file", fileName);
            creditObj.put("title", title);
            creditObj.put("author", author);
            creditObj.put("license", license);
            creditsArray.put(creditObj);

            downloadedCount++;
        }

        // Write captions.txt into staging
        File captionsFile = new File(stagingDir, WallpaperCaptions.FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(captionsFile)) {
            fos.write(captionsText.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Write credits.json into staging
        File creditsFile = new File(stagingDir, "credits.json");
        JSONObject creditsRoot = new JSONObject();
        creditsRoot.put("credits", creditsArray);
        try (FileOutputStream fos = new FileOutputStream(creditsFile)) {
            fos.write(creditsRoot.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        // Write manifest.json into staging
        File manifestFile = new File(stagingDir, "manifest.json");
        try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
            fos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
        }

        // Atomic swap
        File liveDir = new File(baseDir, ThemeFiles.WALLPAPERS);
        File backupDir = new File(baseDir, "wallpapers.old");
        deleteRecursively(backupDir);

        if (liveDir.exists()) {
            if (!liveDir.renameTo(backupDir)) {
                throw new IOException("Не удалось переименовать текущую папку обоев в backup");
            }
        }

        if (!stagingDir.renameTo(liveDir)) {
            if (backupDir.exists()) backupDir.renameTo(liveDir);
            throw new IOException("Не удалось переместить staging в live wallpapers");
        }

        deleteRecursively(backupDir);

        // Save preferences
        sp.edit()
                .putLong(PREF_KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(PREF_KEY_UPDATED_AT, updatedAt)
                .apply();

        Log.i(TAG, "Successfully synced " + downloadedCount + " wallpapers (" + updatedAt + ")");

        final int count = downloadedCount;
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onSyncSuccess(count, true);
            }
        });
    }

    private static void downloadFile(String urlStr, File destFile, String expectedSha256) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "SmartOs-TV/3.3");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP error " + code + " for " + urlStr);
        }

        long length = conn.getContentLengthLong();
        if (length > MAX_FILE_SIZE) {
            throw new IOException("File too large (" + length + " bytes): " + urlStr);
        }

        MessageDigest digest = !expectedSha256.isEmpty() ? MessageDigest.getInstance("SHA-256") : null;

        File tempFile = new File(destFile.getParentFile(), destFile.getName() + ".part");
        long totalRead = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (digest != null) digest.update(buf, 0, n);
                totalRead += n;
                if (totalRead > MAX_FILE_SIZE) {
                    throw new IOException("File exceeded max size while streaming");
                }
            }
            out.flush();
        }

        if (digest != null) {
            String hex = bytesToHex(digest.digest());
            if (!hex.equalsIgnoreCase(expectedSha256)) {
                tempFile.delete();
                throw new IOException("SHA-256 mismatch for " + destFile.getName() + ": expected " + expectedSha256 + ", got " + hex);
            }
        }

        if (destFile.exists()) destFile.delete();
        if (!tempFile.renameTo(destFile)) {
            tempFile.delete();
            throw new IOException("Failed to rename part to " + destFile.getName());
        }
    }

    private static String fetchString(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "SmartOs-TV/3.3");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP error " + code + " for " + urlStr);
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
