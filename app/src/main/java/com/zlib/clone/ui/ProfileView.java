package com.zlib.clone.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.data.Prefs;
import com.zlib.clone.data.Task;
import com.zlib.clone.model.User;
import com.zlib.clone.net.Api;

import java.util.Locale;

/**
 * 我的：账户卡（昵称 / 邮箱 / 会员状态）、今日下载额度卡、设置（服务器地址 / 内容语言）、退出登录。
 *
 * 状态：首屏骨架 / 内容 / 失败重试 / 未登录去登录；刷新时保留已有内容并把按钮切成「加载中…」。
 * 额度语义：downloads_today 是今日已用次数，downloads_limit 是每日上限，剩余 = limit - today。
 */
public class ProfileView extends FrameLayout {

    private static final String[] LANGUAGES = new String[]{"en", "zh", "ru", "es", "fr", "de"};
    private static final int[] LANGUAGE_IDS = new int[]{
            R.id.rb_lang_en, R.id.rb_lang_zh, R.id.rb_lang_ru,
            R.id.rb_lang_es, R.id.rb_lang_fr, R.id.rb_lang_de
    };

    private View accountContent;
    private View accountSkeleton;
    private View accountError;
    private View accountLoggedOut;

    private TextView accountAvatar;
    private TextView accountName;
    private TextView accountEmail;
    private TextView accountMembership;
    private TextView accountErrorText;
    private TextView accountRefreshButton;

    private TextView quotaRefreshButton;
    private TextView quotaNumber;
    private TextView quotaUsed;
    private ProgressBar quotaProgress;

    private TextView settingServerValue;
    private TextView settingLanguageValue;

    private boolean refreshing;
    private boolean hasContent;

    public ProfileView(Context context) {
        super(context);
        init(context);
    }

    public ProfileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_profile, this, true);

        accountContent = findViewById(R.id.ll_account_content);
        accountSkeleton = findViewById(R.id.ll_account_skeleton);
        accountError = findViewById(R.id.ll_account_error);
        accountLoggedOut = findViewById(R.id.ll_account_logged_out);

        accountAvatar = (TextView) findViewById(R.id.tv_account_avatar);
        accountName = (TextView) findViewById(R.id.tv_account_name);
        accountEmail = (TextView) findViewById(R.id.tv_account_email);
        accountMembership = (TextView) findViewById(R.id.tv_account_membership);
        accountErrorText = (TextView) findViewById(R.id.tv_account_error);
        accountRefreshButton = (TextView) findViewById(R.id.btn_account_refresh);

        quotaRefreshButton = (TextView) findViewById(R.id.btn_quota_refresh);
        quotaNumber = (TextView) findViewById(R.id.tv_quota_number);
        quotaUsed = (TextView) findViewById(R.id.tv_quota_used);
        quotaProgress = (ProgressBar) findViewById(R.id.pb_quota);

        settingServerValue = (TextView) findViewById(R.id.tv_setting_server_value);
        settingLanguageValue = (TextView) findViewById(R.id.tv_setting_language_value);

        if (accountRefreshButton != null) {
            accountRefreshButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadProfile();
                }
            });
        }
        if (quotaRefreshButton != null) {
            quotaRefreshButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadProfile();
                }
            });
        }

        View retry = findViewById(R.id.btn_account_retry);
        if (retry != null) {
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadProfile();
                }
            });
        }
        View login = findViewById(R.id.btn_account_login);
        if (login != null) {
            login.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Activity activity = activity();
                    if (activity != null && !activity.isFinishing()) {
                        openLogin(activity);
                    }
                }
            });
        }
        View serverRow = findViewById(R.id.rl_setting_server);
        if (serverRow != null) {
            serverRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showServerDialog();
                }
            });
        }
        View languageRow = findViewById(R.id.rl_setting_language);
        if (languageRow != null) {
            languageRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showLanguageDialog();
                }
            });
        }
        View logout = findViewById(R.id.tv_profile_logout);
        if (logout != null) {
            logout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmLogout();
                }
            });
        }

        updateSettingValues();
        if (!Prefs.isLoggedIn()) {
            showLoggedOut();
        }
    }

    /** MainActivity 切到「我的」tab 时调用。 */
    public void onShown() {
        updateSettingValues();
        if (!Prefs.isLoggedIn()) {
            showLoggedOut();
            return;
        }
        loadProfile();
    }

    private void loadProfile() {
        if (refreshing) {
            return;
        }
        Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (!Prefs.isLoggedIn()) {
            showLoggedOut();
            return;
        }
        refreshing = true;
        if (hasContent) {
            // 刷新时保留已经加载出来的内容，只把按钮切成加载态。
            setRefreshingUi(true);
        } else {
            showSkeleton();
            setRefreshingUi(true);
        }
        Task.run(new Task.Job<User>() {
            @Override
            public User run() throws Exception {
                return Api.get().profile();
            }
        }, new Task.Ok<User>() {
            @Override
            public void ok(User user) {
                refreshing = false;
                Activity activity = activity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                setRefreshingUi(false);
                updateSettingValues();
                if (user == null) {
                    showLoggedOut();
                    return;
                }
                bindAccount(user);
                bindQuota(user);
                hasContent = true;
                showContent();
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                refreshing = false;
                Activity activity = activity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                setRefreshingUi(false);
                updateSettingValues();
                if (Ui.handleAuthError(activity, e)) {
                    return;
                }
                if (hasContent) {
                    // 已有内容：只 toast，不整卡报错。
                    Ui.toast(getContext(), R.string.refresh_failed);
                } else {
                    // 首次失败：整卡错误态 + 重试。
                    showAccountError(Ui.errorText(e));
                    bindQuotaUnknown();
                }
            }
        });
    }

    private void setRefreshingUi(boolean value) {
        refreshing = value;
        if (accountRefreshButton != null) {
            accountRefreshButton.setEnabled(!value);
            accountRefreshButton.setText(value ? R.string.loading : R.string.action_refresh);
        }
        if (quotaRefreshButton != null) {
            quotaRefreshButton.setEnabled(!value);
        }
    }

    private void showSkeleton() {
        Ui.display(accountContent, false);
        Ui.display(accountSkeleton, true);
        Ui.display(accountError, false);
        Ui.display(accountLoggedOut, false);
    }

    private void showContent() {
        Ui.display(accountSkeleton, false);
        Ui.display(accountError, false);
        Ui.display(accountLoggedOut, false);
        Ui.display(accountContent, true);
    }

    private void showAccountError(String message) {
        hasContent = false;
        Ui.display(accountContent, false);
        Ui.display(accountSkeleton, false);
        Ui.display(accountLoggedOut, false);
        Ui.display(accountError, true);
        if (accountErrorText != null) {
            accountErrorText.setText(Ui.text(message, string(R.string.account_load_error)));
        }
    }

    private void showLoggedOut() {
        hasContent = false;
        Ui.display(accountContent, false);
        Ui.display(accountSkeleton, false);
        Ui.display(accountError, false);
        Ui.display(accountLoggedOut, true);
        setRefreshingUi(false);
        bindQuotaUnknown();
        updateSettingValues();
    }

    private void bindAccount(User user) {
        if (user == null) {
            return;
        }
        String name = Ui.text(user.name, "");
        String email = Ui.text(user.email, "");
        String displayName = name.length() > 0 ? name : email;
        if (accountAvatar != null) {
            accountAvatar.setText(initial(displayName));
        }
        if (accountName != null) {
            accountName.setText(displayName);
        }
        if (accountEmail != null) {
            accountEmail.setText(email);
        }
        if (accountMembership != null) {
            // isPremium == 1 才是会员；isConfirmed 是邮箱确认状态，两者不要混用。
            accountMembership.setText(user.isPremium == 1
                    ? R.string.account_member : R.string.account_normal);
        }
    }

    private void bindQuota(User user) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (user == null) {
            bindQuotaUnknown();
            return;
        }
        int today = user.downloadsToday;
        int limit = user.downloadsLimit;
        if (limit <= 0) {
            if (quotaNumber != null) {
                quotaNumber.setText(R.string.quota_unknown);
            }
            if (quotaUsed != null) {
                quotaUsed.setText(context.getString(R.string.quota_used, Math.max(0, today)));
                Ui.display(quotaUsed, true);
            }
            Ui.display(quotaProgress, false);
            return;
        }
        int remaining = limit - today;
        if (remaining < 0) {
            remaining = 0;
        }
        if (quotaNumber != null) {
            quotaNumber.setText(context.getString(R.string.quota_remaining, remaining, limit));
        }
        if (quotaUsed != null) {
            quotaUsed.setText(context.getString(R.string.quota_used, today));
            Ui.display(quotaUsed, true);
        }
        if (quotaProgress != null) {
            Ui.display(quotaProgress, true);
            quotaProgress.setMax(limit);
            int progress = today < 0 ? 0 : (today > limit ? limit : today);
            quotaProgress.setProgress(progress);
        }
    }

    private void bindQuotaUnknown() {
        if (quotaNumber != null) {
            quotaNumber.setText(R.string.quota_unknown);
        }
        Ui.display(quotaUsed, false);
        Ui.display(quotaProgress, false);
    }

    private void updateSettingValues() {
        if (settingServerValue != null) {
            settingServerValue.setText(safeHost());
        }
        if (settingLanguageValue != null) {
            settingLanguageValue.setText(safeLanguage());
        }
    }

    // ---------------------------------------------------------------- 服务器地址

    private void showServerDialog() {
        final Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_server, null);
        final EditText input = (EditText) content.findViewById(R.id.et_dialog_server);
        final TextView error = (TextView) content.findViewById(R.id.tv_dialog_error);
        if (input != null) {
            input.setText(safeHost());
            if (input.getText() != null) {
                input.setSelection(input.getText().length());
            }
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_server_title)
                .setView(content)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        final Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitServer(activity, dialog, input, error, positive);
                }
            });
        }
    }

    private void submitServer(final Activity activity, final AlertDialog dialog,
                              final EditText input, final TextView error,
                              final Button saveButton) {
        String value = input == null || input.getText() == null
                ? "" : input.getText().toString().trim();
        if (!isHttpAddress(value)) {
            if (error != null) {
                error.setText(R.string.dialog_server_invalid);
                Ui.display(error, true);
            }
            return;
        }
        if (error != null) {
            Ui.display(error, false);
        }
        if (saveButton != null) {
            saveButton.setEnabled(false);
        }
        Task.run(new Task.Job<String>() {
            @Override
            public String run() throws Exception {
                return Api.get().probeHost(value);
            }
        }, new Task.Ok<String>() {
            @Override
            public void ok(String resolved) {
                if (saveButton != null) {
                    saveButton.setEnabled(true);
                }
                if (activity.isFinishing()) {
                    return;
                }
                if (resolved != null && resolved.length() > 0) {
                    Api.get().setBase(resolved);
                    Ui.toast(activity, R.string.setting_saved);
                    updateSettingValues();
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    // 站点变了，账户信息需要重新拉一次（MainActivity 没有刷新书库的接口）。
                    loadProfile();
                } else {
                    // 探测失败：保持弹窗，只 toast。
                    Ui.toast(activity, R.string.refresh_failed);
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (saveButton != null) {
                    saveButton.setEnabled(true);
                }
                if (activity.isFinishing()) {
                    return;
                }
                Ui.toast(activity, R.string.refresh_failed);
            }
        });
    }

    private static boolean isHttpAddress(String value) {
        return value != null
                && (value.startsWith("http://") || value.startsWith("https://"));
    }

    // ------------------------------------------------------------------ 内容语言

    private void showLanguageDialog() {
        final Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_language, null);
        final RadioGroup group = (RadioGroup) content.findViewById(R.id.rg_dialog_language);
        if (group != null) {
            int checkedId = checkedIdFor(safeLanguage());
            if (checkedId != 0) {
                group.check(checkedId);
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_language_title)
                .setView(content)
                .setPositiveButton(R.string.action_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (group == null) {
                            return;
                        }
                        String code = codeForCheckedId(group.getCheckedRadioButtonId());
                        if (code == null) {
                            return;
                        }
                        Api.get().setLanguage(code);
                        Ui.toast(activity, R.string.setting_saved);
                        updateSettingValues();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static int checkedIdFor(String code) {
        if (code == null) {
            return 0;
        }
        String normalized = code.trim();
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equalsIgnoreCase(normalized)) {
                return LANGUAGE_IDS[i];
            }
        }
        return 0;
    }

    private static String codeForCheckedId(int checkedId) {
        for (int i = 0; i < LANGUAGE_IDS.length; i++) {
            if (LANGUAGE_IDS[i] == checkedId) {
                return LANGUAGES[i];
            }
        }
        return null;
    }

    // -------------------------------------------------------------------- 退出登录

    private void confirmLogout() {
        final Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Ui.confirm(activity,
                activity.getString(R.string.logout_confirm_title),
                activity.getString(R.string.logout_confirm_message),
                activity.getString(R.string.logout_confirm_action),
                new Runnable() {
                    @Override
                    public void run() {
                        performLogout(activity);
                    }
                });
    }

    private void performLogout(final Activity activity) {
        Task.run(new Task.Job<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Api.get().logout();
                return Boolean.TRUE;
            }
        }, new Task.Ok<Boolean>() {
            @Override
            public void ok(Boolean value) {
                if (activity.isFinishing()) {
                    return;
                }
                openLogin(activity);
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (activity.isFinishing()) {
                    return;
                }
                if (Ui.handleAuthError(activity, e)) {
                    return;
                }
                Ui.toast(activity, Ui.errorText(e));
            }
        });
    }

    private void openLogin(Activity activity) {
        try {
            Intent intent = new Intent(activity, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            activity.finish();
        } catch (Throwable ignored) {
            // 忽略跳转失败。
        }
    }

    // ------------------------------------------------------------------ 小工具

    private Activity activity() {
        Context context = getContext();
        return context instanceof Activity ? (Activity) context : null;
    }

    private String string(int resId) {
        Context context = getContext();
        return context == null ? "" : context.getString(resId);
    }

    private String safeHost() {
        try {
            String host = Prefs.host();
            return host == null ? "" : host;
        } catch (Throwable t) {
            return "";
        }
    }

    private String safeLanguage() {
        try {
            String language = Prefs.language();
            return language == null ? "en" : language;
        } catch (Throwable t) {
            return "en";
        }
    }

    private static String initial(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.US);
    }
}
