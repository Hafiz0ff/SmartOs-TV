package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatchNextAdapter extends RecyclerView.Adapter<WatchNextAdapter.ViewHolder> {
    public interface OnItemClickListener {
        void onItemClick(WatchNextItem item);
    }

    private final Context context;
    private final OnItemClickListener listener;
    private final List<WatchNextItem> items = new ArrayList<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memoryCache;

    public WatchNextAdapter(Context context, OnItemClickListener listener) {
        this.context = context;
        this.listener = listener;

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 12; // 8% of max memory
        this.memoryCache = new LruCache<String, Bitmap>(Math.max(cacheSize, 1024)) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public void setItems(List<WatchNextItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_watch_next, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WatchNextItem item = items.get(position);

        holder.title.setText(item.title != null ? item.title : "");
        if (item.sub != null && !item.sub.isEmpty()) {
            holder.sub.setText(item.sub);
            holder.sub.setVisibility(View.VISIBLE);
        } else {
            holder.sub.setVisibility(View.GONE);
        }

        holder.badge.setText(item.appName != null ? item.appName : item.pkg);

        if (item.progress >= 0.0f) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setProgress(Math.round(item.progress * 100));
        } else {
            holder.progress.setVisibility(View.GONE);
        }

        // Load image
        holder.poster.setImageDrawable(null);
        holder.poster.setTag(item.art);
        loadArtAsync(item.art, item.pkg, holder.poster);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (listener != null) listener.onItemClick(item);
                return true;
            }
            return false;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.06f).scaleY(1.06f).translationZ(8f).setDuration(150).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start();
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void loadArtAsync(String artUri, String pkg, ImageView target) {
        if (artUri == null || artUri.isEmpty()) {
            loadAppIconFallback(pkg, target);
            return;
        }

        Bitmap cached = memoryCache.get(artUri);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        imageExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                if (artUri.startsWith("/")) {
                    File f = new File(artUri);
                    if (f.exists()) {
                        bmp = decodeSampledBitmap(f.getAbsolutePath(), 320, 180);
                    }
                } else if (artUri.startsWith("http://") || artUri.startsWith("https://")) {
                    URL url = new URL(artUri);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4500);
                    conn.setReadTimeout(4500);
                    conn.setInstanceFollowRedirects(true);
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                        in.close();
                        byte[] bytes = baos.toByteArray();
                        if (bytes.length > 0) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                            int reqW = 320, reqH = 180;
                            int sample = 1;
                            if (opts.outHeight > reqH || opts.outWidth > reqW) {
                                int halfH = opts.outHeight / 2;
                                int halfW = opts.outWidth / 2;
                                while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) {
                                    sample *= 2;
                                }
                            }
                            opts.inSampleSize = sample;
                            opts.inJustDecodeBounds = false;
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                        }
                    }
                    conn.disconnect();
                } else if (artUri.startsWith("content://") || artUri.startsWith("android.resource://")) {
                    InputStream in = context.getContentResolver().openInputStream(Uri.parse(artUri));
                    if (in != null) {
                        bmp = BitmapFactory.decodeStream(in);
                        in.close();
                    }
                }
            } catch (Exception ignored) {
            }

            if (bmp != null) {
                memoryCache.put(artUri, bmp);
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    if (artUri.equals(target.getTag())) {
                        target.setImageBitmap(finalBmp);
                    }
                });
            } else {
                mainHandler.post(() -> {
                    if (artUri.equals(target.getTag())) {
                        loadAppIconFallback(pkg, target);
                    }
                });
            }
        });
    }

    private void loadAppIconFallback(String pkg, ImageView target) {
        try {
            Drawable icon = context.getPackageManager().getApplicationIcon(pkg);
            target.setImageDrawable(icon);
            target.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } catch (Exception ignored) {
        }
    }

    private static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        int inSampleSize = 1;
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            int halfH = opts.outHeight / 2;
            int halfW = opts.outWidth / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        opts.inSampleSize = inSampleSize;
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView poster;
        TextView badge;
        TextView title;
        TextView sub;
        ProgressBar progress;

        ViewHolder(View v) {
            super(v);
            poster = v.findViewById(R.id.cardPoster);
            badge = v.findViewById(R.id.cardBadge);
            title = v.findViewById(R.id.cardTitle);
            sub = v.findViewById(R.id.cardSub);
            progress = v.findViewById(R.id.cardProgress);
        }
    }
}
