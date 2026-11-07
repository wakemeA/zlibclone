package com.zlib.clone.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.zlib.clone.net.Api;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简异步图片加载：LruCache + 固定线程池 + HttpURLConnection。
 * 不依赖 OkHttp/Glide/AndroidX。
 */
public final class ImageLoader {

    private static final int THREADS = 4;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;

    private static ImageLoader instance;

    public static synchronized ImageLoader get() {
        if (instance == null) {
            instance = new ImageLoader();
        }
        return instance;
    }

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool;
    private final Handler mainHandler;

    private ImageLoader() {
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheBytes = maxMemory / 8;
        if (cacheBytes <= 0) {
            cacheBytes = 8 * 1024 * 1024;
        }
        cache = new LruCache<String, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getByteCount();
            }
        };
        pool = Executors.newFixedThreadPool(THREADS);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /** 加载封面到 ImageView；相对路径会先经 Api.absolute() 补全。 */
    public void load(final String rawUrl, final ImageView imageView) {
        if (imageView == null) {
            return;
        }
        if (rawUrl == null || rawUrl.trim().length() == 0) {
            imageView.setImageDrawable(null);
            return;
        }
        final String url = absolute(rawUrl);
        imageView.setTag(url);
        Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = fetch(url);
                if (bitmap != null) {
                    cache.put(url, bitmap);
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = imageView.getTag();
                        if (!(tag instanceof String) || !url.equals(tag)) {
                            return; // ImageView 已被复用去显示别的图。
                        }
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap);
                        } else {
                            imageView.setImageDrawable(null);
                        }
                    }
                });
            }
        });
    }

    private static String absolute(String rawUrl) {
        String url = rawUrl.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        try {
            String resolved = Api.get().absolute(url);
            if (resolved != null && resolved.length() > 0) {
                return resolved;
            }
        } catch (Throwable ignored) {
            // 保持原样，下载失败时显示占位。
        }
        return url;
    }

    private Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            in = connection.getInputStream();
            return BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略关闭异常。
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
