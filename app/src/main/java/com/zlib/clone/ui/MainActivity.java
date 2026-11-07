package com.zlib.clone.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zlib.clone.R;

/**
 * 主界面：暖纸白底 + 墨绿主色，底部固定「书库 / 我的书籍 / 阅读 / 我的」四个标签。
 *
 * 设计要点：
 * - 四个页面 View 常驻内存，切换只改可见性，因此搜索词、榜单选择、列表滚动位置都会保留；
 * - 选中项用主色图标/加粗文字 + 浅底胶囊 + contentDescription「已选中」，对读屏友好；
 * - 阅读页进入沉浸模式时隐藏底部导航（ReaderView 通过 Listener 通知）。
 */
public class MainActivity extends Activity {

    private static final String STATE_TAB = "state_tab";

    private FrameLayout container;
    private View navDivider;
    private LinearLayout navBar;
    private LinearLayout[] navItems;
    private ImageView[] navIcons;
    private TextView[] navLabels;

    private LibraryView libraryView;
    private MyBooksView myBooksView;
    private ReaderView readerView;
    private ProfileView profileView;

    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_main);

        container = (FrameLayout) findViewById(R.id.fl_main_container);
        navDivider = findViewById(R.id.v_nav_divider);
        navBar = (LinearLayout) findViewById(R.id.ll_bottom_nav);

        navItems = new LinearLayout[]{
                (LinearLayout) findViewById(R.id.nav_item_library),
                (LinearLayout) findViewById(R.id.nav_item_mybooks),
                (LinearLayout) findViewById(R.id.nav_item_reader),
                (LinearLayout) findViewById(R.id.nav_item_profile)
        };
        navIcons = new ImageView[]{
                (ImageView) findViewById(R.id.iv_nav_library),
                (ImageView) findViewById(R.id.iv_nav_mybooks),
                (ImageView) findViewById(R.id.iv_nav_reader),
                (ImageView) findViewById(R.id.iv_nav_profile)
        };
        navLabels = new TextView[]{
                (TextView) findViewById(R.id.tv_nav_library),
                (TextView) findViewById(R.id.tv_nav_mybooks),
                (TextView) findViewById(R.id.tv_nav_reader),
                (TextView) findViewById(R.id.tv_nav_profile)
        };

        libraryView = new LibraryView(this);
        myBooksView = new MyBooksView(this);
        readerView = new ReaderView(this);
        profileView = new ProfileView(this);

        addFullScreen(libraryView);
        addFullScreen(myBooksView);
        addFullScreen(readerView);
        addFullScreen(profileView);

        readerView.setListener(new ReaderView.Listener() {
            @Override
            public void onGoLibrary() {
                switchToLibraryTab();
            }

            @Override
            public void onImmersiveChanged(boolean immersive) {
                setNavVisible(!immersive);
            }
        });

        for (int i = 0; i < navItems.length; i++) {
            final int index = i;
            if (navItems[i] != null) {
                navItems[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectTab(index);
                    }
                });
            }
        }

        int startTab = (savedInstanceState == null) ? 0
                : savedInstanceState.getInt(STATE_TAB, 0);
        selectTab(startTab);
    }

    private void addFullScreen(View view) {
        container.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void selectTab(int index) {
        if (index < 0 || index > 3) {
            index = 0;
        }
        currentTab = index;
        View[] views = new View[]{libraryView, myBooksView, readerView, profileView};
        for (int i = 0; i < views.length; i++) {
            if (views[i] != null) {
                views[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }

        int primary = getResources().getColor(R.color.primary);
        int muted = getResources().getColor(R.color.ink_secondary);
        String[] labels = new String[]{
                getString(R.string.tab_library),
                getString(R.string.tab_mybooks),
                getString(R.string.tab_reader),
                getString(R.string.tab_profile)
        };
        for (int i = 0; i < navItems.length; i++) {
            boolean selected = (i == index);
            LinearLayout item = navItems[i];
            if (item != null) {
                item.setSelected(selected);
                item.setBackgroundResource(selected ? R.drawable.bg_nav_selected : R.color.transparent);
                item.setContentDescription(selected
                        ? getString(R.string.tab_selected_desc, labels[i]) : labels[i]);
                item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
            if (navIcons[i] != null) {
                navIcons[i].setColorFilter(selected ? primary : muted);
            }
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(selected ? primary : muted);
                navLabels[i].setTypeface(null, selected
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }

        if (index == 0) {
            libraryView.onShown();
        } else if (index == 1) {
            myBooksView.onShown();
        } else if (index == 2) {
            readerView.onShown();
        } else {
            profileView.onShown();
        }
    }

    /** 供空态「去书库」使用。 */
    public void switchToLibraryTab() {
        selectTab(0);
    }

    /**
     * 阅读沉浸态下隐藏/恢复底部导航。
     * 隐藏时把容器底部内边距清零，让 WebView 铺满。
     */
    public void setNavVisible(boolean visible) {
        if (navBar != null) {
            navBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (navDivider != null) {
            navDivider.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        // 阅读沉浸态：优先退出阅读
        if (readerView != null && readerView.isImmersive()) {
            readerView.exitReading();
            return;
        }
        // 阅读页内 WebView 可后退时先后退
        if (currentTab == 2 && readerView != null && readerView.canGoBack()) {
            readerView.goBack();
            return;
        }
        // 书库内搜索态：先退出搜索
        if (currentTab == 0 && libraryView != null && libraryView.handleBack()) {
            return;
        }
        if (currentTab != 0) {
            selectTab(0);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从书籍详情页返回（收藏/下载状态可能变化）时，标记书单需要刷新；
        // 真正切到「我的书籍」tab 时再拉取，避免后台无谓请求。
        if (myBooksView != null) {
            myBooksView.markDirty();
        }
    }

    @Override
    protected void onDestroy() {
        // MainActivity 常驻一个 ReaderView（内含 WebView），必须显式销毁，否则旋转/退出会泄漏。
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
