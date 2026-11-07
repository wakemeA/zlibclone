package com.zlib.clone.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.zlib.clone.data.Prefs;
import com.zlib.clone.data.Task;
import com.zlib.clone.net.Api;
import com.zlib.clone.net.Hosts;

import java.util.ArrayList;
import java.util.List;
import com.zlib.clone.R;

/**
 * 启动页：探活服务器（先试上次的 host，再逐个内置域名），成功后按登录态分流；
 * 全部失败时提供重试与「手动设置服务器地址」。
 */
public class SplashActivity extends Activity {


    private ProgressBar progressBar;
    private TextView statusView;
    private View errorBox;
    private TextView errorView;
    private EditText serverInput;
    private View retryButton;
    private View manualButton;

    private boolean probing = false;
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_splash);

        progressBar = (ProgressBar) findViewById(R.id.pb_splash);
        statusView = (TextView) findViewById(R.id.tv_splash_status);
        errorBox = findViewById(R.id.ll_splash_error);
        errorView = (TextView) findViewById(R.id.tv_splash_error);
        serverInput = (EditText) findViewById(R.id.et_splash_server);
        retryButton = findViewById(R.id.btn_splash_retry);
        manualButton = findViewById(R.id.btn_splash_manual);

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startProbe();
            }
        });
        manualButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualProbe();
            }
        });

        startProbe();
    }

    private void startProbe() {
        if (probing || navigated) {
            return;
        }
        probing = true;
        showLoading();

        Task.run(new Task.Job<String>() {
            @Override
            public String run() throws Exception {
                List<String> candidates = new ArrayList<String>();
                String current = safeHost();
                if (current.length() > 0) {
                    candidates.add(current);
                }
                for (int i = 0; i < Hosts.BUILT_IN.length; i++) {
                    if (!candidates.contains(Hosts.BUILT_IN[i])) {
                        candidates.add(Hosts.BUILT_IN[i]);
                    }
                }
                // 首屏总预算：受限网络（被墙/弱网/Tor）下不能让用户对着启动页等好几分钟。
                // 每个域名探活 4s，这里最多允许累计 30s，超时直接进失败页（可重试/手填地址）。
                long deadline = System.currentTimeMillis() + 30000L;
                for (int i = 0; i < candidates.size(); i++) {
                    if (System.currentTimeMillis() > deadline) {
                        break;
                    }
                    final String candidate = candidates.get(i);
                    postStatus(candidate);
                    String base = null;
                    try {
                        base = Api.get().probeHost(candidate);
                    } catch (Throwable ignored) {
                        base = null;
                    }
                    if (base != null && base.length() > 0) {
                        try {
                            Api.get().setBase(base);
                        } catch (Throwable ignored) {
                            // 写 Prefs 失败不影响本次使用。
                        }
                        return base;
                    }
                }
                return null;
            }
        }, new Task.Ok<String>() {
            @Override
            public void ok(String base) {
                probing = false;
                if (isFinishing() || navigated) {
                    return;
                }
                if (base == null || base.length() == 0) {
                    showError();
                } else {
                    goNext();
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                probing = false;
                if (isFinishing()) {
                    return;
                }
                showError();
            }
        });
    }

    private void manualProbe() {
        final String raw = serverInput.getText() == null ? "" : serverInput.getText().toString().trim();
        if (raw.length() == 0) {
            Ui.toast(this, "请填写服务器地址");
            return;
        }
        if (probing || navigated) {
            return;
        }
        probing = true;
        showLoading();

        Task.run(new Task.Job<String>() {
            @Override
            public String run() throws Exception {
                String base = null;
                try {
                    base = Api.get().probeHost(raw);
                } catch (Throwable ignored) {
                    base = null;
                }
                if (base != null && base.length() > 0) {
                    try {
                        Api.get().setBase(base);
                    } catch (Throwable ignored) {
                        // 忽略写 Prefs 失败。
                    }
                }
                return base;
            }
        }, new Task.Ok<String>() {
            @Override
            public void ok(String base) {
                probing = false;
                if (isFinishing() || navigated) {
                    return;
                }
                if (base == null || base.length() == 0) {
                    Ui.toast(SplashActivity.this, "该地址不可用，请换一个试试");
                    showError();
                } else {
                    goNext();
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                probing = false;
                if (isFinishing()) {
                    return;
                }
                Ui.toast(SplashActivity.this, Ui.errorText(e));
                showError();
            }
        });
    }

    private void postStatus(final String host) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                statusView.setText("正在连接 " + hostName(host) + "…");
            }
        });
    }

    private static String hostName(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void goNext() {
        if (navigated || isFinishing()) {
            return;
        }
        navigated = true;
        boolean loggedIn;
        try {
            loggedIn = Prefs.isLoggedIn();
        } catch (Throwable t) {
            loggedIn = false;
        }
        Intent intent;
        if (loggedIn) {
            intent = new Intent(this, MainActivity.class);
        } else {
            intent = new Intent(this, LoginActivity.class);
        }
        startActivity(intent);
        finish();
    }

    private void showLoading() {
        Ui.display(progressBar, true);
        Ui.display(statusView, true);
        statusView.setText(R.string.splash_connecting);
        Ui.display(errorBox, false);
    }

    private void showError() {
        Ui.display(progressBar, false);
        Ui.display(statusView, false);
        Ui.display(errorBox, true);
        if (errorView != null) {
            errorView.setText(R.string.splash_failed);
        }
        if (serverInput.getText() != null && serverInput.getText().length() == 0) {
            serverInput.setText(safeHost());
        }
    }

    private String safeHost() {
        try {
            String host = Prefs.host();
            return host == null ? "" : host;
        } catch (Throwable t) {
            return "";
        }
    }
}
