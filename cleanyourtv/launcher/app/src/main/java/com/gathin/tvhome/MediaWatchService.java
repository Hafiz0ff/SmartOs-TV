package com.gathin.tvhome;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Continue Watching list. Listens to media sessions (title, artwork, position)
 * without accessibility tree scraping, and queries Android TV TvProvider watch_next_program.
 * Zero CPU background overhead, instant response.
 */
public class MediaWatchService extends NotificationListenerService {
    private static final String TAG = "MediaWatchService";

    MediaSessionManager msm;
    final Map<MediaSession.Token, MediaController.Callback> tracked = new HashMap<>();
    final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    static MediaWatchService instance;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static MediaSessionManager fallbackMsm;
    private static final Map<MediaSession.Token, MediaController.Callback> fallbackTracked = new HashMap<>();
    private static boolean fallbackListenerRegistered = false;
    private static boolean periodicPollScheduled = false;
    private static long lastPollMs = 0L;
    private static Context trackedAppCtx;

    private static final MediaSessionManager.OnActiveSessionsChangedListener fallbackSessionsListener = list -> {
        if (trackedAppCtx != null) {
            syncControllersStatic(trackedAppCtx, list);
        }
    };

    private static final Runnable PERIODIC_POLL_TICK = new Runnable() {
        @Override
        public void run() {
            MAIN_HANDLER.removeCallbacks(this);
            if (trackedAppCtx == null) {
                periodicPollScheduled = false;
                return;
            }
            try {
                pollActiveSessionsImmediate(trackedAppCtx);
            } catch (Throwable ignored) {
            }
            MAIN_HANDLER.postDelayed(this, 4000L);
        }
    };

    @Override
    public void onListenerConnected() {
        instance = this;
        trackedAppCtx = getApplicationContext();
        GuardKeep.ensureHomeKey(this);
        try {
            msm = getSystemService(MediaSessionManager.class);
            if (msm != null) {
                ComponentName me = new ComponentName(this, MediaWatchService.class);
                msm.addOnActiveSessionsChangedListener(sessions, me);
                onSessions(msm.getActiveSessions(me));
            }
        } catch (Throwable e) {
            Log.w(TAG, "media sessions setup: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Throwable ignored) {
        }
        tracked.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null) {
            pollActiveSessionsAsync(this);
        }
    }

    void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController mc : list) {
            if (mc == null || mc.getSessionToken() == null) continue;
            if (!tracked.containsKey(mc.getSessionToken())) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata m) {
                        Store.record(MediaWatchService.this, mc.getPackageName(), m, mc.getPlaybackState(), mc.getQueueTitle());
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState s) {
                        Store.record(MediaWatchService.this, mc.getPackageName(), mc.getMetadata(), s, mc.getQueueTitle());
                    }

                    @Override
                    public void onSessionDestroyed() {
                        tracked.remove(mc.getSessionToken());
                    }
                };
                try {
                    mc.registerCallback(cb, MAIN_HANDLER);
                    tracked.put(mc.getSessionToken(), cb);
                } catch (Throwable ignored) {
                }
            }
            Store.record(this, mc.getPackageName(), mc.getMetadata(), mc.getPlaybackState(), mc.getQueueTitle());
        }
    }

    public static void ensureSessionTracking(Context c) {
        if (c == null) return;
        final Context appCtx = c.getApplicationContext();
        trackedAppCtx = appCtx;
        MAIN_HANDLER.post(() -> {
            try {
                if (!granted(appCtx)) return;

                if (instance == null && Build.VERSION.SDK_INT >= 24) {
                    try {
                        requestRebind(new ComponentName(appCtx, MediaWatchService.class));
                    } catch (Throwable ignored) {
                    }
                }

                if (!periodicPollScheduled) {
                    periodicPollScheduled = true;
                    MAIN_HANDLER.postDelayed(PERIODIC_POLL_TICK, 4000L);
                }

                ComponentName me = new ComponentName(appCtx, MediaWatchService.class);
                if (instance != null && instance.msm != null) {
                    instance.onSessions(instance.msm.getActiveSessions(me));
                    return;
                }

                if (fallbackMsm == null) {
                    fallbackMsm = appCtx.getSystemService(MediaSessionManager.class);
                }
                if (fallbackMsm != null) {
                    if (!fallbackListenerRegistered) {
                        try {
                            fallbackMsm.addOnActiveSessionsChangedListener(fallbackSessionsListener, me, MAIN_HANDLER);
                            fallbackListenerRegistered = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    List<MediaController> active = fallbackMsm.getActiveSessions(me);
                    syncControllersStatic(appCtx, active);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    static void pollActiveSessionsAsync(Context c) {
        if (c == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPollMs < 1500L) return;
        lastPollMs = now;
        ensureSessionTracking(c);
    }

    static void pollActiveSessionsImmediate(Context c) {
        if (c == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPollMs < 250L) return;
        lastPollMs = now;
        ensureSessionTracking(c);
    }

    private static void syncControllersStatic(Context appCtx, List<MediaController> list) {
        if (appCtx == null || list == null) return;
        for (MediaController mc : list) {
            if (mc == null || mc.getSessionToken() == null) continue;
            if (!fallbackTracked.containsKey(mc.getSessionToken())) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata m) {
                        Store.record(appCtx, mc.getPackageName(), m, mc.getPlaybackState(), mc.getQueueTitle());
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState s) {
                        Store.record(appCtx, mc.getPackageName(), mc.getMetadata(), s, mc.getQueueTitle());
                    }

                    @Override
                    public void onSessionDestroyed() {
                        fallbackTracked.remove(mc.getSessionToken());
                    }
                };
                try {
                    mc.registerCallback(cb, MAIN_HANDLER);
                    fallbackTracked.put(mc.getSessionToken(), cb);
                } catch (Throwable ignored) {
                }
            }
            Store.record(appCtx, mc.getPackageName(), mc.getMetadata(), mc.getPlaybackState(), mc.getQueueTitle());
        }
    }

    public static boolean granted(Context c) {
        if (instance != null) return true;
        if (c == null) return false;
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (flat != null && !flat.isEmpty()) {
                ComponentName cn = new ComponentName(c, MediaWatchService.class);
                ComponentName guardCn = new ComponentName(c, GuardRevival.class);
                if (flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString())
                        || flat.contains(guardCn.flattenToString()) || flat.contains(guardCn.flattenToShortString())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                return nm != null && (nm.isNotificationListenerAccessGranted(new ComponentName(c, MediaWatchService.class))
                        || nm.isNotificationListenerAccessGranted(new ComponentName(c, GuardRevival.class)));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * Store and persistence for Continue Watching items.
     */
    public static final class Store {
        static final int MAX = 12;
        static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

        static File file(Context c) {
            return new File(c.getFilesDir(), "continue.json");
        }

        static synchronized JSONArray read(Context c) {
            try {
                File f = file(c);
                if (!f.exists()) return new JSONArray();
                FileInputStream fis = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
                fis.close();
                return new JSONArray(bos.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        static synchronized void write(Context c, JSONArray a) {
            try (OutputStream os = new FileOutputStream(file(c))) {
                os.write(a.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        static String text(MediaMetadata m, String... keys) {
            for (String k : keys) {
                CharSequence s = m.getText(k);
                if (s != null && s.length() > 0) return s.toString();
            }
            return "";
        }

        static synchronized void record(Context c, String pkg, MediaMetadata m, PlaybackState s, CharSequence queueTitle) {
            if (pkg == null || pkg.equals(c.getPackageName()) || "com.android.bluetooth".equals(pkg)) return;
            String title = "";
            String sub = "";
            long dur = 0L;
            if (m != null) {
                title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE,
                        MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_WRITER);
                sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                        MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
                if (sub.equals(title)) sub = "";
                dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            if (title.isEmpty() && queueTitle != null && queueTitle.length() > 0) {
                title = queueTitle.toString().trim();
            }
            if (title.isEmpty()) return;

            long pos = -1;
            int state = PlaybackState.STATE_NONE;
            if (s != null) {
                state = s.getState();
                pos = s.getPosition();
                if (state == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
            }

            String key = pkg + "|" + title;
            JSONArray old = read(c);
            JSONArray out = new JSONArray();
            JSONObject prev = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (key.equals(o.optString("key"))) prev = o;
                else out.put(o);
            }

            if (prev != null) {
                long prevPos = prev.optLong("pos", -1L);
                long prevDur = prev.optLong("dur", 0L);
                if (dur <= 0 && prevDur > 0) dur = prevDur;
                if (prevPos > 5000L && (pos <= 1000L || state == PlaybackState.STATE_STOPPED
                        || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR)) {
                    pos = prevPos;
                }
                if (sub.isEmpty()) sub = prev.optString("sub", "");
            }

            // If finished (>97%), drop
            if (dur > 0 && pos > dur * 0.97) {
                write(c, out);
                return;
            }

            try {
                String art = prev != null ? prev.optString("art") : "";
                Bitmap bmp = m != null ? m.getBitmap(MediaMetadata.METADATA_KEY_ART) : null;
                if (bmp == null && m != null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null && m != null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bmp != null && bmp.getWidth() >= 64) {
                    File dir = new File(c.getFilesDir(), "cw");
                    dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
                    float sc = Math.min(1f, 960f / bmp.getWidth());
                    Bitmap small = sc < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc),
                            Math.round(bmp.getHeight() * sc), true) : bmp;
                    try (OutputStream os = new FileOutputStream(f)) {
                        small.compress(Bitmap.CompressFormat.JPEG, 88, os);
                    }
                    art = f.getAbsolutePath();
                } else if (art.isEmpty() && m != null) {
                    String uri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (uri.startsWith("http") || uri.startsWith("content://") || uri.startsWith("android.resource://")) {
                        art = uri;
                    }
                }

                String link = prev != null ? prev.optString("link", "") : "";
                String metaUri = m != null ? text(m, MediaMetadata.METADATA_KEY_MEDIA_URI) : "";
                if (metaUri.startsWith("http") || metaUri.startsWith("vnd.youtube") || metaUri.contains("://")) {
                    link = metaUri;
                }
                String artUri = m != null ? text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) : "";
                String mediaId = m != null ? text(m, MediaMetadata.METADATA_KEY_MEDIA_ID) : "";

                if (isYouTubePkg(pkg)) {
                    String ytId = extractYouTubeVideoId(metaUri);
                    if (ytId.isEmpty()) ytId = extractYouTubeVideoId(artUri);
                    if (ytId.isEmpty()) ytId = extractYouTubeVideoId(mediaId);
                    if (!ytId.isEmpty()) {
                        link = "https://www.youtube.com/watch?v=" + ytId;
                    }
                }

                JSONObject cur = new JSONObject();
                cur.put("key", key);
                cur.put("pkg", pkg);
                cur.put("title", title);
                cur.put("sub", sub);
                cur.put("art", art);
                cur.put("link", link);
                cur.put("pos", pos);
                cur.put("dur", dur);
                cur.put("ts", System.currentTimeMillis());

                JSONArray head = new JSONArray();
                head.put(cur);
                for (int i = 0; i < out.length() && head.length() < MAX; i++) {
                    head.put(out.get(i));
                }
                write(c, head);
            } catch (Exception e) {
                Log.w(TAG, "write item error: " + e);
            }
        }

        public static List<WatchNextItem> list(Context c) {
            List<WatchNextItem> out = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();
            android.content.SharedPreferences wp = c.getSharedPreferences("watch_next_prefs", Context.MODE_PRIVATE);
            String mode = wp.getString("shelf_mode", "all");

            PackageManager pm = c.getPackageManager();

            if (!"recs_only".equals(mode)) {
                JSONArray a = read(c);
                long now = System.currentTimeMillis();

                // 1. Items from MediaSession
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String pkg = o.optString("pkg");
                    long ts = o.optLong("ts", 0L);
                    if (ts > 0 && now - ts > EXPIRE_MS) continue;

                    if (!isInstalled(pm, pkg)) continue;

                    String key = o.optString("key");
                    if (seenKeys.contains(key)) continue;
                    seenKeys.add(key);

                    WatchNextItem item = new WatchNextItem();
                    item.key = key;
                    item.pkg = pkg;
                    item.appName = getAppName(pm, pkg);
                    item.title = o.optString("title");
                    item.sub = o.optString("sub");
                    item.art = o.optString("art");
                    item.link = o.optString("link");
                    item.pos = o.optLong("pos", -1L);
                    item.dur = o.optLong("dur", 0L);
                    item.progress = item.dur > 0 && item.pos > 0 ? Math.min(1.0f, (float) item.pos / (float) item.dur) : -1.0f;
                    item.engagementTs = ts;
                    out.add(item);
                }

                // 2. Merge Android TV TvProvider watch_next_program
                List<WatchNextItem> tvProviderItems = queryTvProviderWatchNext(c, 8);
                for (WatchNextItem item : tvProviderItems) {
                    if (out.size() >= MAX) break;
                    if (!seenKeys.contains(item.key) && isInstalled(pm, item.pkg)) {
                        seenKeys.add(item.key);
                        out.add(item);
                    }
                }
            }

            // 3. Fill remaining slots with Trending / Latest Releases (Russian region JustWatch / TMDB)
            if (!"continue_only".equals(mode) && out.size() < MAX) {
                List<WatchNextItem> releases = ReleasesManager.getItems(c, MAX - out.size());
                for (WatchNextItem item : releases) {
                    if (out.size() >= MAX) break;
                    if (!seenKeys.contains(item.key)) {
                        seenKeys.add(item.key);
                        out.add(item);
                    }
                }
            }

            return out;
        }

        private static List<WatchNextItem> queryTvProviderWatchNext(Context c, int max) {
            List<WatchNextItem> list = new ArrayList<>();
            Cursor cur = null;
            try {
                Uri uri = Uri.parse("content://android.media.tv/watch_next_program");
                cur = c.getContentResolver().query(uri, null, null, null, null);
                if (cur != null) {
                    PackageManager pm = c.getPackageManager();
                    while (cur.moveToNext() && list.size() < max) {
                        String pkg = colStr(cur, "package_name");
                        String title = colStr(cur, "title");
                        if (pkg.isEmpty() || title.isEmpty() || !isInstalled(pm, pkg)) continue;

                        String epTitle = colStr(cur, "episode_title");
                        String seasonNum = colStr(cur, "season_display_number");
                        String epNum = colStr(cur, "episode_display_number");

                        StringBuilder subSb = new StringBuilder();
                        if (!seasonNum.isEmpty() && !epNum.isEmpty()) {
                            subSb.append('S').append(seasonNum).append(" · E").append(epNum);
                            if (!epTitle.isEmpty()) subSb.append(" · ").append(epTitle);
                        } else if (!epTitle.isEmpty()) {
                            subSb.append(epTitle);
                        }

                        String thumbUri = colStr(cur, "thumbnail_uri");
                        String posterUri = colStr(cur, "poster_art_uri");
                        String art = !thumbUri.isEmpty() ? thumbUri : posterUri;

                        String intentUri = colStr(cur, "intent_uri");
                        long pos = colLong(cur, "last_playback_position_millis", -1L);
                        long dur = colLong(cur, "duration_millis", 0L);

                        WatchNextItem it = new WatchNextItem();
                        it.key = pkg + "|" + title;
                        it.pkg = pkg;
                        it.appName = getAppName(pm, pkg);
                        it.title = title;
                        it.sub = subSb.toString();
                        it.art = art;
                        it.link = intentUri;
                        it.pos = pos;
                        it.dur = dur;
                        it.progress = dur > 0 && pos > 0 ? Math.min(1.0f, (float) pos / (float) dur) : -1.0f;
                        it.engagementTs = colLong(cur, "last_engagement_time_utc_millis", System.currentTimeMillis());
                        list.add(it);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cur != null) {
                    try { cur.close(); } catch (Throwable ignored) { }
                }
            }
            return list;
        }

        private static String colStr(Cursor c, String col) {
            try {
                int idx = c.getColumnIndex(col);
                if (idx >= 0 && !c.isNull(idx)) {
                    String s = c.getString(idx);
                    return s != null ? s.trim() : "";
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

        private static long colLong(Cursor c, String col, long def) {
            try {
                int idx = c.getColumnIndex(col);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            } catch (Throwable ignored) {
            }
            return def;
        }

        private static boolean isInstalled(PackageManager pm, String pkg) {
            if (pkg == null || pkg.isEmpty()) return false;
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static String getAppName(PackageManager pm, String pkg) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                return label != null ? label.toString() : pkg;
            } catch (Exception e) {
                return pkg;
            }
        }

        public static void openContinueItem(Context context, WatchNextItem item, Runnable fallbackAppLaunch) {
            if (context == null || item == null) return;
            final String pkg = item.pkg != null ? item.pkg : "";
            final String link = item.link != null ? item.link.trim() : "";
            final long posMs = item.pos;

            // 0. If package is empty, this is a release/recommendation item
            if (pkg.isEmpty()) {
                ReleasesManager.openTitleInApps(context, item);
                return;
            }

            // 1. Android TV Intent URI
            if (link.startsWith("intent:") || link.contains("#Intent;")) {
                try {
                    Intent parsed = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
                    if (parsed != null) {
                        if (parsed.getPackage() == null && !pkg.isEmpty()) {
                            parsed.setPackage(pkg);
                        }
                        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(parsed);
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }

            // 2. YouTube / SmartTube
            if (isYouTubePkg(pkg)) {
                String ytId = extractYouTubeVideoId(link);
                if (ytId.isEmpty()) ytId = extractYouTubeVideoId(item.art);
                if (!ytId.isEmpty()) {
                    long resumeSec = posMs > 5000L ? (posMs / 1000L) : 0L;
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + ytId + (resumeSec > 0 ? "&t=" + resumeSec + "s" : "")))
                            .setPackage(pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(intent);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 3. Direct Link
            if (!link.isEmpty() && (link.startsWith("http") || link.contains("://"))) {
                try {
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (!pkg.isEmpty()) viewIntent.setPackage(pkg);
                    context.startActivity(viewIntent);
                    return;
                } catch (Throwable ignored) {
                }
            }

            // 4. Fallback: Launch app
            if (fallbackAppLaunch != null) {
                fallbackAppLaunch.run();
            } else {
                PackageManager pm = context.getPackageManager();
                Intent appIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (appIntent == null) appIntent = pm.getLaunchIntentForPackage(pkg);
                if (appIntent != null) {
                    appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(appIntent);
                }
            }
        }

        static boolean isYouTubePkg(String pkg) {
            if (pkg == null) return false;
            String p = pkg.toLowerCase(java.util.Locale.ROOT);
            return p.contains("youtube") || p.contains("smarttube") || p.contains("teamsmart.videomanager");
        }

        static String extractYouTubeVideoId(String raw) {
            if (raw == null || raw.isEmpty()) return "";
            String s = raw.trim();
            if (s.matches("^[a-zA-Z0-9_-]{11}$")) return s;
            Matcher m = Pattern.compile("(?:/vi/|/vi_webp/|v=|youtu\\.be/|/embed/|/shorts/)([a-zA-Z0-9_-]{11})").matcher(s);
            if (m.find()) return m.group(1);
            return "";
        }
    }
}
