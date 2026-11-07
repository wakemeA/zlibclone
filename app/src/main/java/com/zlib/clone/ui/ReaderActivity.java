package com.zlib.clone.ui;

import android.app.Activity;
import android.os.Bundle;

import com.zlib.clone.R;

/**
 * 独立阅读页：书籍详情页点「在线阅读」时进入，内部复用 ReaderView。
 * 阅读页自身不显示底部导航；ReaderView 的「退出阅读」会回到落地页/关闭本页。
 */
public class ReaderActivity extends Activity {

    private ReaderView readerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_reader);

        readerView = (ReaderView) findViewById(R.id.rv_reader);
        String url = getIntent() == null ? null : getIntent().getStringExtra(ReaderView.KEY_URL);
        String title = getIntent() == null ? null : getIntent().getStringExtra(ReaderView.KEY_TITLE);
        if (url == null || url.length() == 0) {
            url = ReaderView.getLastUrl(this);
        }
        if (title == null || title.length() == 0) {
            title = ReaderView.getLastTitle(this);
        }
        if (readerView != null) {
            readerView.setListener(new ReaderView.Listener() {
                @Override
                public void onGoLibrary() {
                    finish();
                }

                @Override
                public void onImmersiveChanged(boolean immersive) {
                    // 独立阅读页没有底部导航，无需处理。
                }
            });
            if (url != null && url.length() > 0) {
                readerView.load(url, title);
            } else {
                readerView.showLanding();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 独立阅读页：返回键直接关闭本页回到书籍详情（页内「退出阅读」仍可回到落地页）。
        if (readerView != null && readerView.isImmersive()) {
            finish();
            return;
        }
        if (readerView != null && readerView.canGoBack()) {
            readerView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // 释放 WebView，避免反复进出阅读页造成内存累积。
        try {
            if (readerView != null) {
                readerView.destroy();
            }
        } catch (Throwable ignored) {
            // 忽略销毁异常。
        }
        super.onDestroy();
    }
}
