package com.zlib.clone.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.data.Prefs;
import com.zlib.clone.net.Api;

import java.util.HashMap;
import java.util.Map;

/**
 * 阅读页：落地态（恢复上次阅读 / 空态 / 错误态）+ 沉浸阅读（WebView）。
 *
 * 交互约定：
 * - 只有进入沉浸阅读时才隐藏底部导航（通过 Listener.onImmersiveChanged 通知 MainActivity）；
 * - 沉浸阅读顶部提供「退出阅读」胶囊按钮，作为明确的退出方式；
 * - 无阅读记录时展示空态与「去书库」；WebView 打不开时展示错误与「重试」。
 *
 * 说明：阅读位置由在线阅读网页自身（domStorage）保存，本地只持久化最后阅读地址与书名，
 * 因此落地页展示「上次阅读」入口而不是伪造的进度百分比（与项目现有能力边界一致）。
 */
public class ReaderView extends FrameLayout {

    public interface Listener {
        /** 空态里点「去书库」。 */
        void onGoLibrary();

        /** 进入/退出沉浸阅读，宿主据此隐藏/恢复底部导航。 */
        void onImmersiveChanged(boolean immersive);
    }

    public static final String KEY_URL = "reader_url";
    public static final String KEY_TITLE = "reader_title";

    private static final String PREFS_NAME = "zlibclone_ui";
    private static final String PREF_LAST_URL = "last_reader_url";
    private static final String PREF_LAST_TITLE = "last_reader_title";

    private WebView webView;
    private ProgressBar progressBar;

    private View landingScroll;
    private View lastCard;
    private View emptyBox;
    private View errorBox;
    private TextView bookView;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView errorTitle;
    private TextView errorDesc;

    private View readingContainer;
    private View exitButton;

    private Listener listener;
    private String loadedUrl;
    private String loadedTitle;
    private boolean reading;

    public ReaderView(Context context) {
        super(context);
        init(context);
    }

    public ReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ReaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    @SuppressWarnings("deprecation")
    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_reader, this, true);

        webView = (WebView) findViewById(R.id.wv_reader);
        progressBar = (ProgressBar) findViewById(R.id.pb_reader);
        landingScroll = findViewById(R.id.sv_reader_landing);
        lastCard = findViewById(R.id.ll_reader_last_card);
        emptyBox = findViewById(R.id.ll_reader_empty);
        errorBox = findViewById(R.id.ll_reader_error);
        bookView = (TextView) findViewById(R.id.tv_reader_book);
        emptyTitle = (TextView) findViewById(R.id.tv_reader_empty_title);
        emptyDesc = (TextView) findViewById(R.id.tv_reader_empty_desc);
        errorTitle = (TextView) findViewById(R.id.tv_reader_error_title);
        errorDesc = (TextView) findViewById(R.id.tv_reader_error_desc);
        readingContainer = findViewById(R.id.fl_reader_reading);
        exitButton = findViewById(R.id.btn_reader_exit);

        View continueButton = findViewById(R.id.btn_reader_continue);
        if (continueButton != null) {
            continueButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String url = getLastUrl(getContext());
                    if (url != null && url.length() > 0) {
                        loadAndRead(url, getLastTitle(getContext()));
                    }
                }
            });
        }
        View goLibrary = findViewById(R.id.btn_reader_go_library);
        if (goLibrary != null) {
            goLibrary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onGoLibrary();
                    }
                }
            });
        }
        View retry = findViewById(R.id.btn_reader_retry);
        if (retry != null) {
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showLanding();
                    String url = getLastUrl(getContext());
                    if (url != null && url.length() > 0) {
                        loadAndRead(url, getLastTitle(getContext()));
                    }
                }
            });
        }
        if (exitButton != null) {
            exitButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitReading();
                }
            });
        }

        setupWebView();
        showLanding();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        if (webView == null) {
            return;
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webView.setBackgroundColor(getResources().getColor(R.color.card));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                boolean mainFrame = failingUrl == null || failingUrl.equals(view.getUrl());
                if (mainFrame) {
                    showError();
                }
            }
        });
    }

    /** 打开在线阅读地址（相对路径会经 Api.absolute 补全）。 */
    public void load(String urlOrPath, String title) {
        String absoluteUrl = toAbsolute(urlOrPath);
        if (absoluteUrl == null || absoluteUrl.length() == 0) {
            showError();
            return;
        }
        saveLastUrl(getContext(), absoluteUrl);
        saveLastTitle(getContext(), title);
        loadAndRead(absoluteUrl, title);
    }

    private void loadAndRead(String absoluteUrl, String title) {
        loadedUrl = absoluteUrl;
        loadedTitle = title;
        reading = true;
        if (readingContainer != null) {
            readingContainer.setVisibility(View.VISIBLE);
        }
        if (landingScroll != null) {
            landingScroll.setVisibility(View.GONE);
        }
        injectCookies(absoluteUrl);
        if (webView != null) {
            Map<String, String> headers = safeHeaders();
            try {
                if (headers != null && !headers.isEmpty()) {
                    webView.loadUrl(absoluteUrl, headers);
                } else {
                    webView.loadUrl(absoluteUrl);
                }
            } catch (Throwable t) {
                try {
                    webView.loadUrl(absoluteUrl);
                } catch (Throwable ignored) {
                    showError();
                }
            }
        }
        if (listener != null) {
            listener.onImmersiveChanged(true);
        }
    }

    /** 退出沉浸阅读，回到落地页并恢复底部导航。 */
    public void exitReading() {
        reading = false;
        showLanding();
        if (listener != null) {
            listener.onImmersiveChanged(false);
        }
    }

    /** 切到阅读 tab 时调用。 */
    public void onShown() {
        if (reading) {
            return;
        }
        showLanding();
    }

    public boolean isImmersive() {
        return reading;
    }

    /** 落地页：有上次记录显示卡片，否则显示空态。 */
    public void showLanding() {
        reading = false;
        if (landingScroll != null) {
            landingScroll.setVisibility(View.VISIBLE);
        }
        if (readingContainer != null) {
            readingContainer.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        String lastUrl = getLastUrl(getContext());
        boolean hasLast = lastUrl != null && lastUrl.length() > 0;

        if (emptyTitle != null) {
            emptyTitle.setText(R.string.reader_empty_title);
        }
        if (emptyDesc != null) {
            emptyDesc.setText(R.string.reader_empty_desc);
        }
        Ui.display(lastCard, hasLast);
        Ui.display(emptyBox, !hasLast);
        Ui.display(errorBox, false);
        if (hasLast && bookView != null) {
            String title = getLastTitle(getContext());
            bookView.setText(Ui.text(title, getResources().getString(R.string.reader_last_caption)));
        }
    }

    private void showError() {
        reading = false;
        if (landingScroll != null) {
            landingScroll.setVisibility(View.VISIBLE);
        }
        if (readingContainer != null) {
            readingContainer.setVisibility(View.GONE);
        }
        if (errorTitle != null) {
            errorTitle.setText(R.string.reader_error_title);
        }
        if (errorDesc != null) {
            errorDesc.setText(R.string.reader_error_desc);
        }
        Ui.display(lastCard, false);
        Ui.display(emptyBox, false);
        Ui.display(errorBox, true);
        if (listener != null) {
            listener.onImmersiveChanged(false);
        }
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    @SuppressWarnings("deprecation")
    private void injectCookies(String url) {
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                manager.setAcceptThirdPartyCookies(webView, true);
            }
            int userId = Prefs.userId();
            String userKey = Prefs.userKey();
            if (userId > 0 && userKey != null && userKey.length() > 0) {
                String cookie = "remix_userId=" + userId + "; remix_userkey=" + userKey;
                manager.setCookie(baseHost(), cookie);
                manager.setCookie(url, cookie);
                manager.flush();
            }
        } catch (Throwable ignored) {
            // Cookie 注入失败不阻塞页面打开。
        }
    }

    private static String baseHost() {
        String base = null;
        try {
            base = Api.get().base();
        } catch (Throwable ignored) {
            base = null;
        }
        if (base == null || base.length() == 0) {
            base = "https://z-lib.sk/";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return base;
        }
        return "https://" + base;
    }

    private static Map<String, String> safeHeaders() {
        try {
            Map<String, String> raw = Api.get().headers();
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Map<String, String> clean = new HashMap<String, String>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    clean.put(entry.getKey(), entry.getValue());
                }
            }
            return clean;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String toAbsolute(String urlOrPath) {
        if (urlOrPath == null) {
            return null;
        }
        String value = urlOrPath.trim();
        if (value.length() == 0) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        try {
            String resolved = Api.get().absolute(value);
            if (resolved != null && resolved.length() > 0) {
                return resolved;
            }
        } catch (Throwable ignored) {
            // 回落原始值。
        }
        return value;
    }

    public static String getLastUrl(Context context) {
        return readPref(context, PREF_LAST_URL);
    }

    public static void saveLastUrl(Context context, String url) {
        if (context == null || url == null || url.length() == 0) {
            return;
        }
        writePref(context, PREF_LAST_URL, url);
    }

    public static String getLastTitle(Context context) {
        return readPref(context, PREF_LAST_TITLE);
    }

    public static void saveLastTitle(Context context, String title) {
        if (title == null || title.length() == 0) {
            return;
        }
        writePref(context, PREF_LAST_TITLE, title);
    }

    private static String readPref(Context context, String key) {
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writePref(Context context, String key, String value) {
        if (context == null || value == null || value.length() == 0) {
            return;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(key, value).apply();
        } catch (Throwable ignored) {
            // 忽略本地保存失败。
        }
    }

    /** 销毁内嵌 WebView（Activity onDestroy 时调用，避免 WebView 随旋转/进出不断泄漏）。 */
    public void destroy() {
        reading = false;
        WebView view = webView;
        webView = null;
        if (view == null) {
            return;
        }
        try {
            view.stopLoading();
        } catch (Throwable ignored) {
            // 忽略。
        }
        try {
            view.setWebViewClient(null);
            view.setWebChromeClient(null);
        } catch (Throwable ignored) {
            // 忽略。
        }
        try {
            view.loadUrl("about:blank");
        } catch (Throwable ignored) {
            // 忽略。
        }
        try {
            view.clearHistory();
        } catch (Throwable ignored) {
            // 忽略。
        }
        try {
            view.removeAllViews();
        } catch (Throwable ignored) {
            // 忽略。
        }
        try {
            view.destroy();
        } catch (Throwable ignored) {
            // 忽略。
        }
    }
}
