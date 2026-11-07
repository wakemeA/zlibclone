package com.zlib.clone;

import android.app.Application;
import android.util.Log;

import com.zlib.clone.data.Prefs;

import java.io.File;
import java.io.PrintWriter;
import java.util.Date;

/**
 * 应用入口：初始化偏好设置（服务器地址/登录态/语言），并安装一个最简单的崩溃日志记录器。
 * 其余初始化（探活、登录态跳转）由 SplashActivity 负责。
 *
 * 崩溃日志写在应用私有目录 files/last_crash.txt，便于真机排查时提供信息，不影响正常流程。
 */
public class App extends Application {

    private static final String TAG = "ZLibClone";
    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        installCrashLogger();
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    File file = new File(getFilesDir(), CRASH_FILE);
                    PrintWriter writer = new PrintWriter(file);
                    writer.println("time: " + new Date().toString());
                    writer.println("thread: " + (thread == null ? "?" : thread.getName()));
                    if (throwable != null) {
                        throwable.printStackTrace(writer);
                    }
                    writer.close();
                    Log.e(TAG, "crash logged to " + file.getAbsolutePath(), throwable);
                } catch (Throwable ignored) {
                    // 记录失败也必须继续走系统默认处理。
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }
}
