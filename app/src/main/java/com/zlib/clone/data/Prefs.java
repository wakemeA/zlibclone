package com.zlib.clone.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.zlib.clone.model.User;

import java.util.Locale;

/**
 * 全局偏好设置（SharedPreferences 封装）。
 *
 * 所有方法都对"未 init"的场景做了兜底（返回默认值 / 不写入），
 * 但正常流程要求 {@code App.onCreate()} 里先调用 {@link #init(Context)}。
 *
 * 已登录判定：userId > 0 且 userKey 非空（与原始 App 语义一致）。
 */
public final class Prefs {

    private static final String FILE_NAME = "zlibclone_prefs";

    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_KEY = "remix_userkey";
    private static final String KEY_HOST = "host";
    private static final String KEY_LANGUAGE = "language";

    private static final String DEFAULT_HOST = "https://zlibrary.se/";
    private static final String DEFAULT_LANGUAGE = "en";

    private static Context sContext;
    private static SharedPreferences sPrefs;

    private Prefs() {
    }

    public static void init(Context c) {
        if (c == null) {
            return;
        }
        Context app = c.getApplicationContext();
        sContext = (app != null) ? app : c;
        sPrefs = sContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences sp() {
        if (sPrefs == null && sContext != null) {
            sPrefs = sContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }
        return sPrefs;
    }

    // ---------------------------------------------------------------- 登录态

    public static boolean isLoggedIn() {
        return userId() > 0 && userKey().length() > 0;
    }

    public static int userId() {
        SharedPreferences p = sp();
        if (p == null) {
            return 0;
        }
        try {
            return p.getInt(KEY_USER_ID, 0);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    public static String userKey() {
        SharedPreferences p = sp();
        if (p == null) {
            return "";
        }
        try {
            String key = p.getString(KEY_USER_KEY, "");
            return (key == null) ? "" : key;
        } catch (ClassCastException e) {
            return "";
        }
    }

    public static void setUser(User u) {
        if (u == null) {
            clearUser();
            return;
        }
        SharedPreferences p = sp();
        if (p == null) {
            return;
        }
        p.edit()
                .putInt(KEY_USER_ID, u.id)
                .putString(KEY_USER_KEY, (u.remixUserkey == null) ? "" : u.remixUserkey)
                .apply();
    }

    public static void clearUser() {
        SharedPreferences p = sp();
        if (p == null) {
            return;
        }
        p.edit()
                .remove(KEY_USER_ID)
                .remove(KEY_USER_KEY)
                .apply();
    }

    // ------------------------------------------------------------------ 服务器

    public static String host() {
        SharedPreferences p = sp();
        String raw = null;
        if (p != null) {
            try {
                raw = p.getString(KEY_HOST, DEFAULT_HOST);
            } catch (ClassCastException e) {
                raw = null;
            }
        }
        return normalizeHost(raw);
    }

    public static void setHost(String host) {
        SharedPreferences p = sp();
        if (p == null) {
            return;
        }
        p.edit().putString(KEY_HOST, normalizeHost(host)).apply();
    }

    // -------------------------------------------------------------------- 语言

    public static String language() {
        SharedPreferences p = sp();
        String lang = null;
        if (p != null) {
            try {
                lang = p.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
            } catch (ClassCastException e) {
                lang = null;
            }
        }
        if (lang == null) {
            return DEFAULT_LANGUAGE;
        }
        lang = lang.trim();
        return (lang.length() == 0) ? DEFAULT_LANGUAGE : lang;
    }

    public static void setLanguage(String lang) {
        SharedPreferences p = sp();
        if (p == null) {
            return;
        }
        String value = (lang == null) ? DEFAULT_LANGUAGE : lang.trim();
        if (value.length() == 0) {
            value = DEFAULT_LANGUAGE;
        }
        p.edit().putString(KEY_LANGUAGE, value).apply();
    }

    // -------------------------------------------------------------------- 内部

    /**
     * 规范化 host：
     * - 缺 scheme 时补 "https://"（.onion 地址补 "http://"）
     * - 去掉结尾多余的 "/"，最后统一补一个 "/"（保证 host() 永远以 "/" 结尾）
     * - null / 空串 → 默认 "https://zlibrary.se/"
     */
    private static String normalizeHost(String raw) {
        if (raw == null) {
            return DEFAULT_HOST;
        }
        String host = raw.trim();
        if (host.length() == 0) {
            return DEFAULT_HOST;
        }
        String lower = host.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.endsWith(".onion")) {
                host = "http://" + host;
            } else {
                host = "https://" + host;
            }
            lower = host.toLowerCase(Locale.US);
        }
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) {
            return DEFAULT_HOST;
        }
        String scheme = host.substring(0, schemeEnd + 3);
        String rest = host.substring(schemeEnd + 3);
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.length() == 0) {
            return DEFAULT_HOST;
        }
        return scheme + rest + "/";
    }
}
