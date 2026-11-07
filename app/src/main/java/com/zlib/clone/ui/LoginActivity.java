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
import com.zlib.clone.model.User;
import com.zlib.clone.net.Api;
import com.zlib.clone.R;

/**
 * 登录页：邮箱 + 密码 + 可编辑服务器地址，成功进入 MainActivity。
 */
public class LoginActivity extends Activity {

    private EditText emailInput;
    private EditText passwordInput;
    private EditText serverInput;
    private View loginButton;
    private ProgressBar progressBar;
    private TextView errorView;

    private boolean submitting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_login);

        emailInput = (EditText) findViewById(R.id.et_login_email);
        passwordInput = (EditText) findViewById(R.id.et_login_password);
        serverInput = (EditText) findViewById(R.id.et_login_server);
        loginButton = findViewById(R.id.btn_login);
        progressBar = (ProgressBar) findViewById(R.id.pb_login);
        errorView = (TextView) findViewById(R.id.tv_login_error);

        serverInput.setText(safeHost());
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                login();
            }
        });
    }

    private void login() {
        if (submitting) {
            return;
        }
        final String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
        final String password = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
        final String server = serverInput.getText() == null ? "" : serverInput.getText().toString().trim();

        if (email.length() == 0 || password.length() == 0) {
            showError("请输入邮箱和密码");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("请输入正确的邮箱地址");
            return;
        }
        if (password.length() < 6) {
            showError("密码长度至少 6 位");
            return;
        }

        submitting = true;
        setBusy(true);
        Ui.display(errorView, false);

        Task.run(new Task.Job<User>() {
            @Override
            public User run() throws Exception {
                if (server.length() > 0) {
                    Api.get().setBase(server);
                }
                return Api.get().login(email, password);
            }
        }, new Task.Ok<User>() {
            @Override
            public void ok(User user) {
                submitting = false;
                if (isFinishing()) {
                    return;
                }
                setBusy(false);
                if (user == null) {
                    showError("登录失败，请稍后重试");
                    return;
                }
                try {
                    Prefs.setUser(user);
                } catch (Throwable ignored) {
                    // CORE 的 login 通常也会写，这里双保险。
                }
                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                finish();
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                submitting = false;
                if (isFinishing()) {
                    return;
                }
                setBusy(false);
                String message = Ui.errorText(e);
                showError(message);
                Ui.toast(LoginActivity.this, message);
            }
        });
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        Ui.display(progressBar, busy);
    }

    private void showError(String message) {
        errorView.setText(message);
        Ui.display(errorView, true);
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
