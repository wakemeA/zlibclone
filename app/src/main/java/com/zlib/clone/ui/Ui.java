package com.zlib.clone.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import com.zlib.clone.data.Prefs;
import com.zlib.clone.net.ApiException;

/**
 * UI 层公共工具：初始化、dp、Toast、异常文案、显隐、401 处理、确认弹窗。
 * 改版后颜色统一走 @color 资源（paper / ink / ink_secondary / primary / danger …），
 * 这里不再提供硬编码色值常量。
 */
public final class Ui {

    private Ui() {
    }

    /** 防御性初始化 CORE 的 Prefs（App.onCreate 正常也会调，Prefs.init 幂等）。 */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        try {
            Prefs.init(context.getApplicationContext());
        } catch (Throwable ignored) {
            // CORE 已在 Application 中初始化过；这里失败不阻塞 UI。
        }
    }

    public static void toast(Context context, String message) {
        if (context == null || message == null || message.length() == 0) {
            return;
        }
        try {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Toast 失败不影响流程。
        }
    }

    public static void toast(Context context, int stringRes) {
        if (context == null) {
            return;
        }
        try {
            toast(context, context.getString(stringRes));
        } catch (Throwable ignored) {
            // 资源缺失时忽略。
        }
    }

    /** 把异常统一转成可展示的中文文案。 */
    public static String errorText(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        if (e instanceof ApiException) {
            ApiException api = (ApiException) e;
            if (api.serverError != null && api.serverError.length() > 0) {
                return api.serverError;
            }
            if (api.httpCode == 401 || api.httpCode == 403) {
                return "登录已过期或没有权限，请重新登录";
            }
            if (api.httpCode == 513) {
                // DiamWall 反爬挑战未通过（常见于 z-lib.sk 等镜像）。
                return "服务器反爬校验未通过，请点击重试（会自动切换服务器）";
            }
            if (api.httpCode > 0) {
                return "请求失败（HTTP " + api.httpCode + "）";
            }
            String msg = api.getMessage();
            return friendlyNetworkText(msg, "网络错误，请稍后重试");
        }
        String message = e.getMessage();
        String fallback = "网络错误，请稍后重试";
        if (e instanceof java.net.SocketTimeoutException) {
            fallback = "连接服务器超时，请检查网络或更换服务器地址";
        } else if (e instanceof java.net.UnknownHostException) {
            fallback = "无法解析服务器地址，请检查网络或更换服务器";
        }
        return friendlyNetworkText(message, fallback);
    }

    /** 把 OkHttp/HttpURLConnection 的英文网络异常翻译成用户能理解的中文提示。 */
    private static String friendlyNetworkText(String message, String fallback) {
        if (message == null || message.length() == 0) {
            return fallback;
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        if (lower.contains("too many follow-up")
                || lower.contains("diamwall")
                || message.contains("重定向")) {
            return "服务器反爬/跳转异常，请点击重试（会自动切换服务器）";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "连接服务器超时，请检查网络或更换服务器地址";
        }
        if (lower.contains("unable to resolve host") || lower.contains("unknownhost")) {
            return "无法解析服务器地址，请检查网络或更换服务器";
        }
        if (lower.contains("no route to host") || lower.contains("network is unreachable")) {
            return "网络不可用，请检查网络连接或更换服务器";
        }
        return message;
    }

    public static void display(View view, boolean visible) {
        if (view == null) {
            return;
        }
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static int dp(Context context, float value) {
        if (context == null) {
            return (int) (value + 0.5f);
        }
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()) + 0.5f);
    }

    /** 空串兜底。 */
    public static String text(String value, String fallback) {
        return (value == null || value.trim().length() == 0) ? fallback : value.trim();
    }

    /**
     * 统一处理登录失效：401/403 时清理本地登录态、跳转登录页并结束当前页面。
     *
     * @return true 表示已按登录失效处理，调用方不要再展示自己的错误态。
     */
    public static boolean handleAuthError(Activity activity, Throwable e) {
        if (!(e instanceof ApiException)) {
            return false;
        }
        int code = ((ApiException) e).httpCode;
        if (code != 401 && code != 403) {
            return false;
        }
        if (activity == null) {
            return true;
        }
        try {
            Prefs.clearUser();
        } catch (Throwable ignored) {
            // 忽略。
        }
        toast(activity, "登录已过期，请重新登录");
        try {
            Intent intent = new Intent(activity, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            activity.finish();
        } catch (Throwable ignored) {
            // 忽略跳转失败。
        }
        return true;
    }

    /** 二次确认弹窗（沿用 framework AlertDialog）。 */
    public static void confirm(Activity activity, String title, String message,
                               String positive, final Runnable onConfirm) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            if (title != null) {
                builder.setTitle(title);
            }
            if (message != null) {
                builder.setMessage(message);
            }
            builder.setNegativeButton(activity.getString(com.zlib.clone.R.string.action_cancel), null);
            builder.setPositiveButton(positive, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                }
            });
            builder.show();
        } catch (Throwable ignored) {
            // 弹窗失败不影响主流程。
        }
    }
}
