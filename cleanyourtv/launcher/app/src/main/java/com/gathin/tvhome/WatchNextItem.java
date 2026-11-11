package com.gathin.tvhome;

public class WatchNextItem {
    public String key;
    public String pkg;
    public String appName;
    public String title;
    public String sub;
    public String art;
    public String link;
    public long pos;
    public long dur;
    public float progress; // 0.0 .. 1.0 or -1 if unknown
    public long engagementTs;

    public long kinopoiskId;
    public String contentType; // "movie" | "serial"
    public long iviId;

    public WatchNextItem() {
    }

    public WatchNextItem(String pkg, String appName, String title, String sub, String art, String link, long pos, long dur) {
        this.pkg = pkg;
        this.appName = appName;
        this.title = title;
        this.sub = sub;
        this.art = art;
        this.link = link;
        this.pos = pos;
        this.dur = dur;
        this.progress = dur > 0 && pos > 0 ? Math.min(1.0f, (float) pos / (float) dur) : -1.0f;
        this.key = pkg + "|" + title;
        this.engagementTs = System.currentTimeMillis();
    }
}
