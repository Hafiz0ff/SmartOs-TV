package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ReleasesManager {
    private static final String TAG = "ReleasesManager";
    private static final String CACHE_FILE = "releases_cache_v2.json";
    private static final String PREF_KP_CACHE = "kinopoisk_id_cache";

    private static final String JW_GRAPHQL = "https://apis.justwatch.com/graphql";
    private static final String JW_QUERY = "query P($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
            + " popularTitles(country: $country, filter: $filter, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {"
            + " edges { node { id objectType content(country: $country, language: $language) {"
            + " title originalReleaseYear shortDescription genres { shortName } backdrops { backdropUrl } scoring { imdbScore } }"
            + " } } } }";

    private static List<WatchNextItem> memoryCache = null;
    private static long lastFetchTime = 0L;

    public static class KinopoiskMatch {
        public final long id;
        public final String type; // "movie" | "serial"

        public KinopoiskMatch(long id, String type) {
            this.id = id;
            this.type = type != null ? type : "movie";
        }
    }

    private ReleasesManager() {}

    public static synchronized void invalidateCache() {
        memoryCache = null;
        lastFetchTime = 0L;
    }

    private static long getCacheTtlMs(Context context) {
        int hours = context.getSharedPreferences("watch_next_prefs", Context.MODE_PRIVATE)
                .getInt("recs_interval_hours", 6);
        if (hours <= 0) return 0L;
        return hours * 3600L * 1000L;
    }

    public static synchronized List<WatchNextItem> getItems(Context context, int maxItems) {
        if (context == null || maxItems <= 0) return Collections.emptyList();

        long now = System.currentTimeMillis();
        long cacheTtl = getCacheTtlMs(context);

        if (memoryCache != null && !memoryCache.isEmpty() && (cacheTtl > 0 && (now - lastFetchTime) < cacheTtl)) {
            return selectRotated(context, memoryCache, maxItems);
        }

        // Try disk cache first
        List<WatchNextItem> fromDisk = loadFromDisk(context);
        if (fromDisk != null && !fromDisk.isEmpty()) {
            memoryCache = fromDisk;
            if (cacheTtl > 0 && (now - lastFetchTime) < cacheTtl) {
                return selectRotated(context, memoryCache, maxItems);
            }
        }

        // Fetch fresh items online
        List<WatchNextItem> online = fetchOnline(context);
        if (online != null && !online.isEmpty()) {
            memoryCache = online;
            lastFetchTime = now;
            saveToDisk(context, online);
            return selectRotated(context, online, maxItems);
        }

        // If online failed, return whatever we had on disk or built-in fallbacks
        if (memoryCache != null && !memoryCache.isEmpty()) {
            return selectRotated(context, memoryCache, maxItems);
        }

        List<WatchNextItem> fallbacks = getFallbackTitles();
        memoryCache = fallbacks;
        return selectRotated(context, fallbacks, maxItems);
    }

    private static List<WatchNextItem> selectRotated(Context context, List<WatchNextItem> list, int max) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        int hours = context.getSharedPreferences("watch_next_prefs", Context.MODE_PRIVATE)
                .getInt("recs_interval_hours", 6);
        long seed;
        if (hours <= 0) {
            seed = System.currentTimeMillis();
        } else {
            seed = System.currentTimeMillis() / (hours * 3600L * 1000L);
        }
        List<WatchNextItem> copy = new ArrayList<>(list);
        Collections.shuffle(copy, new java.util.Random(seed));
        return truncate(copy, max);
    }

    private static List<WatchNextItem> truncate(List<WatchNextItem> list, int max) {
        if (list == null) return Collections.emptyList();
        if (list.size() <= max) return new ArrayList<>(list);
        return new ArrayList<>(list.subList(0, max));
    }

    private static List<WatchNextItem> fetchOnline(Context context) {
        HttpURLConnection conn = null;
        try {
            JSONObject filter = new JSONObject();
            JSONArray objectTypes = new JSONArray();
            objectTypes.put("MOVIE");
            objectTypes.put("SHOW");
            filter.put("objectTypes", objectTypes);

            JSONObject vars = new JSONObject();
            vars.put("country", "RU");
            vars.put("language", "ru");
            vars.put("first", 36);
            vars.put("filter", filter);

            JSONObject body = new JSONObject();
            body.put("query", JW_QUERY);
            body.put("variables", vars);

            byte[] postData = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(JW_GRAPHQL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "JustWatch HTTP " + code);
                return null;
            }

            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject response = new JSONObject(sb.toString());
            JSONArray edges = response.optJSONObject("data")
                    .optJSONObject("popularTitles")
                    .optJSONArray("edges");

            if (edges == null || edges.length() == 0) return null;

            List<WatchNextItem> out = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.optJSONObject(i).optJSONObject("node");
                if (node == null) continue;
                String objType = node.optString("objectType", "MOVIE");
                JSONObject content = node.optJSONObject("content");
                if (content == null) continue;

                String title = content.optString("title", "").trim();
                if (title.isEmpty()) continue;

                int year = content.optInt("originalReleaseYear", 0);
                String desc = content.optString("shortDescription", "").trim();

                JSONObject scoring = content.optJSONObject("scoring");
                double imdb = scoring != null ? scoring.optDouble("imdbScore", 0.0) : 0.0;

                JSONArray bds = content.optJSONArray("backdrops");
                String artUrl = "";
                if (bds != null && bds.length() > 0) {
                    JSONObject bdObj = bds.optJSONObject(0);
                    if (bdObj != null) {
                        String rawBd = bdObj.optString("backdropUrl", "");
                        if (!rawBd.isEmpty()) {
                            artUrl = "https://images.justwatch.com" + rawBd.replace("{profile}", "s1440").replace("{format}", "jpg");
                        }
                    }
                }

                WatchNextItem item = new WatchNextItem();
                item.key = "release:" + title;
                item.pkg = "";
                boolean isShow = "SHOW".equalsIgnoreCase(objType);
                item.appName = isShow ? "Сериал" : "Фильм";
                item.title = title;
                item.contentType = isShow ? "serial" : "movie";

                // Check if already in persistent Kinopoisk ID cache
                KinopoiskMatch match = getCachedKinopoiskMatch(context, title);
                if (match != null && match.id > 0) {
                    item.kinopoiskId = match.id;
                    if (match.type != null && !match.type.isEmpty()) {
                        item.contentType = match.type;
                    }
                }

                StringBuilder subSb = new StringBuilder();
                if (year > 0) subSb.append(year);
                if (imdb > 0.0) {
                    if (subSb.length() > 0) subSb.append(" · ");
                    subSb.append(String.format(Locale.US, "★ %.1f", imdb));
                }
                if (!desc.isEmpty()) {
                    if (subSb.length() > 0) subSb.append(" · ");
                    subSb.append(desc);
                }
                item.sub = subSb.toString();
                item.art = artUrl;
                item.pos = -1L;
                item.dur = 0L;
                item.progress = -1.0f;
                item.engagementTs = System.currentTimeMillis();

                out.add(item);
            }
            return out;
        } catch (Throwable e) {
            Log.w(TAG, "fetchOnline error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<WatchNextItem> loadFromDisk(Context context) {
        try {
            File f = new File(context.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            lastFetchTime = f.lastModified();

            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
            fis.close();

            JSONArray arr = new JSONArray(bos.toString(StandardCharsets.UTF_8.name()));
            List<WatchNextItem> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                WatchNextItem item = new WatchNextItem();
                item.key = o.optString("key");
                item.pkg = o.optString("pkg");
                item.appName = o.optString("appName");
                item.title = o.optString("title");
                item.sub = o.optString("sub");
                item.art = o.optString("art");
                item.kinopoiskId = o.optLong("kpId", 0L);
                item.contentType = o.optString("cType", "movie");
                item.iviId = o.optLong("iviId", 0L);
                item.pos = -1L;
                item.dur = 0L;
                item.progress = -1.0f;
                item.engagementTs = o.optLong("ts", System.currentTimeMillis());

                if (item.kinopoiskId == 0) {
                    KinopoiskMatch match = getCachedKinopoiskMatch(context, item.title);
                    if (match != null && match.id > 0) {
                        item.kinopoiskId = match.id;
                        if (match.type != null && !match.type.isEmpty()) {
                            item.contentType = match.type;
                        }
                    }
                }
                out.add(item);
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void saveToDisk(Context context, List<WatchNextItem> items) {
        if (context == null || items == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (WatchNextItem item : items) {
                JSONObject o = new JSONObject();
                o.put("key", item.key);
                o.put("pkg", item.pkg != null ? item.pkg : "");
                o.put("appName", item.appName != null ? item.appName : "");
                o.put("title", item.title != null ? item.title : "");
                o.put("sub", item.sub != null ? item.sub : "");
                o.put("art", item.art != null ? item.art : "");
                o.put("kpId", item.kinopoiskId);
                o.put("cType", item.contentType != null ? item.contentType : "");
                o.put("iviId", item.iviId);
                o.put("ts", item.engagementTs);
                arr.put(o);
            }
            File f = new File(context.getFilesDir(), CACHE_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<WatchNextItem> getFallbackTitles() {
        List<WatchNextItem> list = new ArrayList<>();

        WatchNextItem i1 = new WatchNextItem("", "Фильм", "Сто лет тому вперёд", "2024 · Фантастика, Приключения", "", "", -1, 0);
        i1.kinopoiskId = 1334852L;
        i1.contentType = "movie";
        list.add(i1);

        WatchNextItem i2 = new WatchNextItem("", "Сериал", "Слово пацана. Кровь на асфальте", "2023 · Драма, Криминал", "", "", -1, 0);
        i2.kinopoiskId = 5304403L;
        i2.contentType = "serial";
        list.add(i2);

        WatchNextItem i3 = new WatchNextItem("", "Фильм", "Мастер и Маргарита", "2024 · Драма, Фэнтези", "", "", -1, 0);
        i3.kinopoiskId = 1115471L;
        i3.contentType = "movie";
        list.add(i3);

        WatchNextItem i4 = new WatchNextItem("", "Сериал", "Художник", "2022 · Детектив, Триллер", "", "", -1, 0);
        i4.kinopoiskId = 4317342L;
        i4.contentType = "serial";
        list.add(i4);

        WatchNextItem i5 = new WatchNextItem("", "Фильм", "Лёд 3", "2024 · Мелодрама, Спорт", "", "", -1, 0);
        i5.kinopoiskId = 5275429L;
        i5.contentType = "movie";
        list.add(i5);

        WatchNextItem i6 = new WatchNextItem("", "Сериал", "Трасса", "2024 · Психологический триллер", "", "", -1, 0);
        i6.kinopoiskId = 5305583L;
        i6.contentType = "serial";
        list.add(i6);

        return list;
    }

    // --------------------------------------------------------------------------
    // KINOPOISK SUGGEST RESOLVER & PERSISTENT CACHE
    // --------------------------------------------------------------------------

    private static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zа-я0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static KinopoiskMatch getCachedKinopoiskMatch(Context context, String title) {
        if (context == null || title == null) return null;
        String key = normalizeTitle(title);
        if (key.isEmpty()) return null;
        SharedPreferences sp = context.getSharedPreferences(PREF_KP_CACHE, Context.MODE_PRIVATE);
        String val = sp.getString(key, null);
        if (val == null) return null;
        String[] parts = val.split(":");
        if (parts.length >= 2) {
            try {
                long id = Long.parseLong(parts[0]);
                return new KinopoiskMatch(id, parts[1]);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void saveCachedKinopoiskMatch(Context context, String title, long id, String type) {
        if (context == null || title == null) return;
        String key = normalizeTitle(title);
        if (key.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_KP_CACHE, Context.MODE_PRIVATE);
        sp.edit().putString(key, id + ":" + (type != null ? type : "movie")).apply();
    }

    public static KinopoiskMatch resolveKinopoiskId(Context context, String title) {
        if (context == null || title == null || title.trim().isEmpty()) return null;
        KinopoiskMatch cached = getCachedKinopoiskMatch(context, title);
        if (cached != null) return cached;

        HttpURLConnection conn = null;
        try {
            String enc = URLEncoder.encode(title.trim(), StandardCharsets.UTF_8.name());
            URL url = new URL("https://www.kinopoisk.ru/api/suggest/v2/?query=" + enc + "&limit=5");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "KP suggest HTTP " + code + " for " + title);
                return null;
            }

            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONObject suggest = root.optJSONObject("suggest");
            if (suggest == null) return null;

            // 1. Check top result
            JSONObject top = suggest.optJSONObject("top");
            if (top != null) {
                JSONObject topRes = top.optJSONObject("topResult");
                if (topRes != null) {
                    JSONObject global = topRes.optJSONObject("global");
                    if (global != null) {
                        long id = global.optLong("id", 0L);
                        if (id > 0) {
                            String typeName = global.optString("__typename", "");
                            String type = typeName.toLowerCase(Locale.ROOT).contains("series") ? "serial" : "movie";
                            saveCachedKinopoiskMatch(context, title, id, type);
                            return new KinopoiskMatch(id, type);
                        }
                    }
                }
            }

            // 2. Fallback to movies array
            JSONArray movies = suggest.optJSONArray("movies");
            if (movies != null && movies.length() > 0) {
                for (int i = 0; i < movies.length(); i++) {
                    JSONObject mObj = movies.optJSONObject(i);
                    if (mObj == null) continue;
                    JSONObject mv = mObj.optJSONObject("movie");
                    if (mv == null) mv = mObj;
                    long id = mv.optLong("id", 0L);
                    if (id > 0) {
                        String typeName = mv.optString("__typename", "");
                        String type = typeName.toLowerCase(Locale.ROOT).contains("series") ? "serial" : "movie";
                        saveCachedKinopoiskMatch(context, title, id, type);
                        return new KinopoiskMatch(id, type);
                    }
                }
            }

            // If nothing found, cache 0 to prevent re-querying continuously
            saveCachedKinopoiskMatch(context, title, 0L, "none");
            return null;
        } catch (Throwable e) {
            Log.w(TAG, "resolveKinopoiskId error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void prewarmShelfItems(Context context, List<WatchNextItem> items) {
        if (context == null || items == null || items.isEmpty()) return;
        new Thread(() -> {
            for (WatchNextItem it : items) {
                if (it == null || it.title == null || it.title.trim().isEmpty()) continue;
                if (it.kinopoiskId > 0 && it.contentType != null && !it.contentType.isEmpty()) continue;
                KinopoiskMatch m = getCachedKinopoiskMatch(context, it.title);
                if (m == null) {
                    m = resolveKinopoiskId(context, it.title);
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                }
                if (m != null && m.id > 0) {
                    it.kinopoiskId = m.id;
                    it.contentType = m.type;
                }
            }
        }).start();
    }

    // --------------------------------------------------------------------------
    // TITLE LAUNCH ROUTING
    // --------------------------------------------------------------------------

    public static void openTitleInApps(Context context, WatchNextItem item) {
        if (context == null || item == null) return;
        String q = item.title != null ? item.title.trim() : "";
        if (q.isEmpty()) return;

        PackageManager pm = context.getPackageManager();
        SharedPreferences sp = context.getSharedPreferences("watch_next_prefs", Context.MODE_PRIVATE);
        String player = sp.getString("default_player", "auto");

        // 1. Explicit app selections without deep link support (HDRezka, Kinotrek, VK)
        if ("rezka".equals(player)) {
            Toast.makeText(context, "Прямых ссылок нет, открываю HDRezka...", Toast.LENGTH_SHORT).show();
            openAppHome(context, pm, "com.raccoon.hdrezkav2", "app.cartv.tv");
            return;
        }
        if ("kinotrek".equals(player)) {
            Toast.makeText(context, "Прямых ссылок нет, открываю Кинотрек...", Toast.LENGTH_SHORT).show();
            openAppHome(context, pm, "org.trackertv.player");
            return;
        }
        if ("vk".equals(player)) {
            Toast.makeText(context, "Прямых ссылок нет, открываю VK Видео...", Toast.LENGTH_SHORT).show();
            openAppHome(context, pm, "com.vk.tv");
            return;
        }

        // 2. Ivi explicit selection
        if ("ivi".equals(player)) {
            if (item.iviId > 0) {
                try {
                    Intent iviIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ivi.ru/watch/" + item.iviId))
                            .setPackage("ru.ivi.client.global")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(iviIntent);
                    return;
                } catch (Throwable ignored) {}
            }
            Toast.makeText(context, "Прямой запуск для Ivi недоступен, открываю главный экран...", Toast.LENGTH_SHORT).show();
            openAppHome(context, pm, "ru.ivi.client.global");
            return;
        }

        // 3. Auto or Zona: Direct launch via Kinopoisk ID deep link
        if (!isInstalled(pm, "mobi.zona")) {
            Toast.makeText(context, "Приложение Zona не установлено", Toast.LENGTH_LONG).show();
            return;
        }

        if (item.kinopoiskId > 0 && item.contentType != null && !item.contentType.isEmpty()) {
            if (launchZonaDirect(context, item.kinopoiskId, item.contentType)) {
                return;
            }
        }

        // Check persistent cache
        KinopoiskMatch cached = getCachedKinopoiskMatch(context, q);
        if (cached != null) {
            if (cached.id > 0) {
                item.kinopoiskId = cached.id;
                item.contentType = cached.type;
                launchZonaDirect(context, cached.id, cached.type);
                return;
            } else {
                Toast.makeText(context, "Фильм «" + q + "» не найден в каталоге", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Resolve online asynchronously with visual feedback
        Toast.makeText(context, "Поиск «" + q + "»...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            KinopoiskMatch match = resolveKinopoiskId(context, q);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (match != null && match.id > 0) {
                    item.kinopoiskId = match.id;
                    item.contentType = match.type;
                    launchZonaDirect(context, match.id, match.type);
                } else {
                    Toast.makeText(context, "Не удалось найти «" + q + "» в каталоге Кинопоиска", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static boolean launchZonaDirect(Context context, long kpId, String contentType) {
        if (context == null || kpId <= 0) return false;
        try {
            String type = "serial".equalsIgnoreCase(contentType) ? "serial" : "movie";
            String url = "https://mobi.zona/" + type + "/" + kpId;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setComponent(new ComponentName("mobi.zona", "ru.zona.app.android.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "launchZonaDirect error: " + e.getMessage());
            return false;
        }
    }

    private static boolean openAppHome(Context context, PackageManager pm, String... packages) {
        if (context == null || pm == null || packages == null) return false;
        for (String pkg : packages) {
            if (!isInstalled(pm, pkg)) continue;
            try {
                Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return false;
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
