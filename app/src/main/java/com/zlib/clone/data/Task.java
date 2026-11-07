package com.zlib.clone.data;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简后台任务工具：把阻塞的 Job 丢到后台线程池执行，
 * 成功/失败回调 post 回主线程（Looper.getMainLooper()）。
 *
 * 用法：
 * <pre>
 * Task.run(new Task.Job&lt;Page&lt;Book&gt;&gt;() {
 *     public Page&lt;Book&gt; run() throws Exception {
 *         return Api.get().mostPopular(1);
 *     }
 * }, new Task.Ok&lt;Page&lt;Book&gt;&gt;() {
 *     public void ok(Page&lt;Book&gt; page) { ... }
 * }, new Task.Err() {
 *     public void err(Exception e) { ... }
 * });
 * </pre>
 */
public final class Task {

    public interface Job<T> {
        T run() throws Exception;
    }

    public interface Ok<T> {
        void ok(T v);
    }

    public interface Err {
        void err(Exception e);
    }

    private static final int POOL_SIZE = 4;

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger(0);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "zlib-task-" + THREAD_COUNT.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile Handler sMainHandler;

    private Task() {
    }

    public static <T> void run(final Job<T> job, final Ok<T> ok, final Err err) {
        if (job == null) {
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T value;
                try {
                    value = job.run();
                } catch (Throwable t) {
                    final Exception error;
                    if (t instanceof Exception) {
                        error = (Exception) t;
                    } else {
                        error = new RuntimeException(t);
                    }
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (err != null) {
                                err.err(error);
                            }
                        }
                    });
                    return;
                }
                final T result = value;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok != null) {
                            ok.ok(result);
                        }
                    }
                });
            }
        });
    }

    private static void post(Runnable r) {
        Handler handler = mainHandler();
        if (handler != null) {
            handler.post(r);
        } else {
            // 极端场景（没有主 Looper）：退化为当前线程直接执行，保证回调不会丢。
            r.run();
        }
    }

    private static Handler mainHandler() {
        Handler handler = sMainHandler;
        if (handler == null) {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                handler = new Handler(looper);
                sMainHandler = handler;
            }
        }
        return handler;
    }
}
