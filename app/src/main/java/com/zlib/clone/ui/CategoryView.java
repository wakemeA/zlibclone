package com.zlib.clone.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.data.Categories;
import com.zlib.clone.data.CategoryNames;
import com.zlib.clone.data.Task;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.Category;
import com.zlib.clone.model.CategoryPage;
import com.zlib.clone.net.Api;
import com.zlib.clone.net.BookMapper;
import com.zlib.clone.net.Hosts;
import com.zlib.clone.net.WebCategorySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 「书库 → 分类」页面（网页分类数据源 + 原生界面）。
 *
 * 三层结构（保留网站原始分类树，不做多分类合并）：
 * 1) 一级分类目录（/categories 解析结果）；
 * 2) 某个一级分类下的二级分类（首行「浏览全部 · 一级分类」= 该一级分类自身的书单）；
 * 3) 分类书单：分类 + 语言（AND）+ 分页，书目进入原生详情或回退网页详情。
 *
 * 顶栏联动：L0 走完整书库 chrome（26sp 标题 + 全库搜索框），L1/L2 通过 {@link ChromeHost}
 * 切换为紧凑模式（只保留 36dp 分段栏），并显示固定 48dp 的双段面包屑（‹ 全部分类 / 一级分类）。
 *
 * 状态区分：首次加载（按层级显示列表/网格两种骨架）、当前条件没有结果（空态）、网络失败、
 * 需要完成网页验证、页面结构变化解析失败、分类不存在 —— 不再统一显示「书源暂未提供内容分类」。
 */
public class CategoryView extends FrameLayout {

    private static final int LEVEL_ROOT = 0;
    private static final int LEVEL_CHILDREN = 1;
    private static final int LEVEL_BOOKS = 2;

    private static final int WIDE_SCREEN_DP = 600;
    private static final int COLUMNS_PHONE = 2;
    private static final int COLUMNS_WIDE = 3;
    private static final int PAGINATION_THRESHOLD = 4;

    /** 语言筛选：值是网页 GET 参数 languages[] 的真实取值（已实测），空串表示不限语言。 */
    private static final String[] LANGUAGE_VALUES = Categories.LANGUAGE_VALUES;

    /** 书籍详情映射缓存（容量 64，Key 为 book.id 或网页详情地址）：命中后跳过二次网络映射。 */
    private static final LruCache<String, Book> MAPPING_CACHE = new LruCache<String, Book>(64);

    /** 宿主编排接口：分类视图钻取层级变化时通知书库页切换紧凑/完整顶栏。 */
    public interface ChromeHost {
        /** @param compact true = L1/L2（隐藏书库标题与全库搜索框，仅保留分段栏） */
        void setChromeCompact(boolean compact);
    }

    private ChromeHost chromeHost;

    private View breadcrumbBar;
    private TextView backRootButton;
    private TextView backParentButton;
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout languageRow;
    private HorizontalScrollView languageBar;
    private ListView categoryList;
    private GridView bookGrid;
    private LinearLayout skeleton;
    private LinearLayout skeletonList;
    private LinearLayout skeletonGrid;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView emptyAction;
    private LinearLayout errorState;
    private TextView errorTitle;
    private TextView errorDesc;
    private TextView errorRetry;
    private TextView loadingMore;

    private CategoryRowAdapter rowAdapter;
    private BookAdapter bookAdapter;

    /** 预创建的语言 Chip：切换语言时只更新选中态/字重，不重建 View。 */
    private final List<LanguageChip> languageChips = new ArrayList<LanguageChip>();

    private final List<Category> roots = new ArrayList<Category>();
    private final List<Row> rows = new ArrayList<Row>();
    private String baseUrl = "";
    private Category parent;
    private Category category;
    private int level = LEVEL_ROOT;
    private String language = "";
    private int page = 0;
    private int totalPages = 1;
    private boolean hasMore = true;
    private boolean loading = false;
    private int requestToken = 0;
    private String pageUrlPattern = "";
    private boolean started = false;

    /** L0 / L1 列表滚动位置记忆（返回上一级时精准恢复）。 */
    private int l0ScrollIndex = 0;
    private int l0ScrollTop = 0;
    private int l1ScrollIndex = 0;
    private int l1ScrollTop = 0;

    /** 最近一次分类请求失败是否值得先切换镜像再重试（分类不存在时换镜像无用）。 */
    private boolean lastFailureServerSide = true;

    /** 映射加载弹窗与请求 token：取消后递增 token，丢弃在途回调。 */
    private AlertDialog mappingDialog;
    private int mappingToken = 0;

    public CategoryView(Context context) {
        super(context);
        init(context);
    }

    public CategoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CategoryView(Context context, ChromeHost host) {
        super(context);
        this.chromeHost = host;
        init(context);
    }

    // =====================================================================
    // 初始化
    // =====================================================================

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_categories, this, true);

        breadcrumbBar = findViewById(R.id.ll_cat_breadcrumb);
        backRootButton = (TextView) findViewById(R.id.btn_cat_back_root);
        backParentButton = (TextView) findViewById(R.id.btn_cat_back_parent);
        titleView = (TextView) findViewById(R.id.tv_cat_title);
        subtitleView = (TextView) findViewById(R.id.tv_cat_subtitle);
        languageRow = (LinearLayout) findViewById(R.id.ll_cat_languages);
        languageBar = (HorizontalScrollView) findViewById(R.id.hsv_cat_languages);
        categoryList = (ListView) findViewById(R.id.lv_cat_categories);
        bookGrid = (GridView) findViewById(R.id.gv_cat_books);
        skeleton = (LinearLayout) findViewById(R.id.ll_cat_skeleton);
        skeletonList = (LinearLayout) findViewById(R.id.ll_cat_skeleton_list);
        skeletonGrid = (LinearLayout) findViewById(R.id.ll_cat_skeleton_grid);
        emptyState = (LinearLayout) findViewById(R.id.ll_cat_empty);
        emptyTitle = (TextView) findViewById(R.id.tv_cat_empty_title);
        emptyDesc = (TextView) findViewById(R.id.tv_cat_empty_desc);
        emptyAction = (TextView) findViewById(R.id.btn_cat_empty_action);
        errorState = (LinearLayout) findViewById(R.id.ll_cat_error);
        errorTitle = (TextView) findViewById(R.id.tv_cat_error_title);
        errorDesc = (TextView) findViewById(R.id.tv_cat_error_desc);
        errorRetry = (TextView) findViewById(R.id.btn_cat_error_retry);
        loadingMore = (TextView) findViewById(R.id.tv_cat_loading_more);

        rowAdapter = new CategoryRowAdapter(context, rows);
        if (categoryList != null) {
            categoryList.setAdapter(rowAdapter);
            categoryList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapter, View view, int position, long id) {
                    onRowClicked(position);
                }
            });
        }

        bookAdapter = new BookAdapter(context);
        if (bookGrid != null) {
            bookGrid.setNumColumns(resolveColumns(context));
            bookGrid.setAdapter(bookAdapter);
            bookGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapter, View view, int position, long id) {
                    openBook(position);
                }
            });
            bookGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    // 不需要处理滚动状态。
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem,
                                     int visibleItemCount, int totalItemCount) {
                    if (level != LEVEL_BOOKS || loading || !hasMore || totalItemCount <= 0) {
                        return;
                    }
                    if (firstVisibleItem + visibleItemCount + PAGINATION_THRESHOLD >= totalItemCount) {
                        loadBooks(page + 1, false);
                    }
                }
            });
        }

        if (backRootButton != null) {
            backRootButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 跨级返回：L1 / L2 直接回到 L0，并恢复 L0 目录的滚动位置。
                    renderRoot(true);
                }
            });
        }
        if (backParentButton != null) {
            backParentButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // L2 → 对应 L1（带上滚动位置恢复）。
                    if (parent != null) {
                        openChildren(parent, true);
                    } else {
                        renderRoot(true);
                    }
                }
            });
        }
        if (emptyAction != null) {
            emptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (level == LEVEL_BOOKS && language.length() > 0) {
                        selectLanguage("");
                    } else if (level == LEVEL_BOOKS) {
                        loadBooks(1, true);
                    } else {
                        loadTree();
                    }
                }
            });
        }
        if (errorRetry != null) {
            errorRetry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retryWithFailover();
                }
            });
        }

        createLanguageChips();
        updateHeader();
    }

    private static int resolveColumns(Context context) {
        if (context == null) {
            return COLUMNS_PHONE;
        }
        Configuration configuration = context.getResources().getConfiguration();
        if (configuration != null && configuration.screenWidthDp >= WIDE_SCREEN_DP) {
            return COLUMNS_WIDE;
        }
        return COLUMNS_PHONE;
    }

    // =====================================================================
    // 对外入口
    // =====================================================================

    /** LibraryView 切到「分类」分段时调用；只在首次进入时加载分类目录。 */
    public void onShown() {
        syncChrome();
        if (started) {
            return;
        }
        started = true;
        loadTree();
    }

    /** 把当前层级的顶栏状态同步给宿主（切回分类分段时调用，避免残留紧凑/完整状态）。 */
    public void syncChrome() {
        if (chromeHost != null) {
            chromeHost.setChromeCompact(level != LEVEL_ROOT);
        }
    }

    /** 返回键：书单→二级列表→一级目录；已在目录层返回 false（由外层回到「热门」）。 */
    public boolean handleBack() {
        if (level == LEVEL_BOOKS) {
            if (parent != null) {
                openChildren(parent, true);
            } else {
                renderRoot(true);
            }
            return true;
        }
        if (level == LEVEL_CHILDREN) {
            renderRoot(true);
            return true;
        }
        return false;
    }

    // =====================================================================
    // 一级分类目录
    // =====================================================================

    private void loadTree() {
        final int token = ++requestToken;
        loading = true;
        showSkeletonStateForLevel();
        Task.run(new Task.Job<WebCategorySource.Tree>() {
            @Override
            public WebCategorySource.Tree run() throws Exception {
                return WebCategorySource.fetchTree();
            }
        }, new Task.Ok<WebCategorySource.Tree>() {
            @Override
            public void ok(WebCategorySource.Tree tree) {
                if (token != requestToken) {
                    return;
                }
                loading = false;
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                if (tree == null || tree.roots.isEmpty()) {
                    showErrorState(getContext().getString(R.string.category_error_parse));
                    return;
                }
                baseUrl = tree.baseUrl;
                roots.clear();
                roots.addAll(tree.roots);
                renderRoot(false);
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != requestToken || level != LEVEL_BOOKS) {
                    return;
                }
                loading = false;
                lastFailureServerSide = isServerSideFailure(e);
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                showErrorState(categoryErrorMessage(e));
            }
        });
    }

    private void renderRoot(boolean restoreScroll) {
        invalidatePendingRequests();
        level = LEVEL_ROOT;
        parent = null;
        category = null;
        page = 0;
        totalPages = 1;
        hasMore = true;
        rows.clear();
        for (Category root : roots) {
            Row row = new Row();
            row.category = root;
            row.title = root.title();
            row.subtitle = root.hasChineseName() ? root.sourceName : "";
            row.count = root.children.size() > 0
                    ? getContext().getString(R.string.category_children_short, root.children.size()) : "";
            rows.add(row);
        }
        rowAdapter.notifyDataSetChanged();
        updateHeader();
        if (rows.isEmpty()) {
            showErrorState(getContext().getString(R.string.category_error_parse));
        } else {
            showListState();
            if (restoreScroll) {
                restoreCategoryScroll(l0ScrollIndex, l0ScrollTop);
            }
        }
    }

    private void openChildren(Category value) {
        openChildren(value, false);
    }

    private void openChildren(Category value, boolean restoreScroll) {
        invalidatePendingRequests();
        level = LEVEL_CHILDREN;
        parent = value;
        category = null;
        page = 0;
        totalPages = 1;
        hasMore = true;
        rows.clear();
        Row all = new Row();
        all.all = true;
        all.title = getContext().getString(R.string.category_browse_all, value.title());
        all.subtitle = getContext().getString(R.string.category_browse_all_subtitle);
        all.count = "";
        rows.add(all);
        for (Category child : value.children) {
            Row row = new Row();
            row.category = child;
            row.title = child.title();
            row.subtitle = child.hasChineseName() ? child.sourceName : readableType(child.type);
            row.count = child.bookCount >= 0
                    ? getContext().getString(R.string.category_books_count, child.bookCount) : "";
            rows.add(row);
        }
        rowAdapter.notifyDataSetChanged();
        updateHeader();
        showListState();
        if (restoreScroll) {
            restoreCategoryScroll(l1ScrollIndex, l1ScrollTop);
        }
    }

    private void onRowClicked(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        if (level == LEVEL_ROOT) {
            if (row.category != null) {
                captureCategoryScroll(LEVEL_ROOT);
                openChildren(row.category);
            }
            return;
        }
        if (level == LEVEL_CHILDREN) {
            Category target = row.all ? parent : row.category;
            if (target != null) {
                captureCategoryScroll(LEVEL_CHILDREN);
                openBooks(target);
            }
        }
    }

    // =====================================================================
    // 分类书单
    // =====================================================================

    private void openBooks(Category target) {
        level = LEVEL_BOOKS;
        category = target;
        language = "";
        page = 0;
        totalPages = 1;
        hasMore = true;
        pageUrlPattern = "";
        updateLanguageChips();
        updateHeader();
        loadBooks(1, true);
    }

    private void selectLanguage(String value) {
        if (value == null) {
            value = "";
        }
        if (value.equals(language)) {
            return;
        }
        language = value;
        page = 0;
        totalPages = 1;
        pageUrlPattern = "";
        updateLanguageChips();
        updateHeader();
        scrollSelectedLanguageToCenter(value);
        // 切换语言 = 同分类下重新筛选：保留当前书单做半透明过渡，避免骨架屏闪烁。
        loadBooks(1, true, true);
    }

    private void loadBooks(final int targetPage, final boolean reset) {
        loadBooks(targetPage, reset, false);
    }

    /**
     * 请求一页分类书单。
     *
     * @param targetPage              目标页（从 1 开始）
     * @param reset                   是否重置分页（切分类/切语言/重试为 true，滚动分页为 false）
     * @param preserveContentOnReset  重置时是否保留当前书单并做半透明过渡（仅切换语言时使用）
     */
    private void loadBooks(final int targetPage, final boolean reset,
                           final boolean preserveContentOnReset) {
        final int token = ++requestToken;
        final Category requestCategory = category;
        final String requestLanguage = language;
        final String requestPattern = reset ? "" : pageUrlPattern;
        if (requestCategory == null) {
            return;
        }
        loading = true;
        final boolean keepContent = reset && preserveContentOnReset
                && bookGrid != null
                && bookGrid.getVisibility() == View.VISIBLE
                && bookAdapter.getCount() > 0;
        if (reset) {
            page = 0;
            hasMore = true;
            pageUrlPattern = "";
            if (keepContent) {
                startContentTransition();
            } else {
                endContentTransition();
                bookAdapter.clear();
                if (bookGrid != null) {
                    bookGrid.setSelection(0);
                }
                Ui.display(loadingMore, false);
                showSkeletonStateForLevel();
            }
        } else {
            if (loadingMore != null) {
                loadingMore.setText(R.string.loading_more);
            }
            Ui.display(loadingMore, true);
        }

        Task.run(new Task.Job<CategoryPage>() {
            @Override
            public CategoryPage run() throws Exception {
                return WebCategorySource.fetchBooks(baseUrl, requestCategory, requestLanguage,
                        targetPage, requestPattern);
            }
        }, new Task.Ok<CategoryPage>() {
            @Override
            public void ok(CategoryPage result) {
                if (token != requestToken || level != LEVEL_BOOKS) {
                    return;
                }
                loading = false;
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                Ui.display(skeleton, false);
                Ui.display(loadingMore, false);
                if (keepContent) {
                    endContentTransition();
                }

                CategoryPage value = (result == null) ? new CategoryPage() : result;
                page = value.current > 0 ? value.current : targetPage;
                totalPages = value.totalPages > 0 ? value.totalPages : page;
                hasMore = value.hasMore();
                if (value.pageUrlPattern != null && value.pageUrlPattern.length() > 0) {
                    pageUrlPattern = value.pageUrlPattern;
                }
                if (reset) {
                    bookAdapter.setItems(value.items);
                    if (bookGrid != null) {
                        bookGrid.setSelection(0);
                    }
                } else if (!value.items.isEmpty()) {
                    bookAdapter.addItems(value.items);
                }
                updateHeader();
                if (value.empty || bookAdapter.getCount() == 0) {
                    showEmptyState();
                } else {
                    showContentState();
                    refreshPaginationHint();
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != requestToken) {
                    return;
                }
                loading = false;
                lastFailureServerSide = isServerSideFailure(e);
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                Ui.display(skeleton, false);
                Ui.display(loadingMore, false);
                endContentTransition();
                String message = categoryErrorMessage(e);
                if (!reset && bookAdapter.getCount() > 0) {
                    // 分页失败：保留已加载书单，只提示，可继续滚动重试。
                    Ui.toast(getContext(), message);
                    showContentState();
                    refreshPaginationHint();
                } else {
                    showErrorState(message);
                }
            }
        });
    }

    /** L2 分页结束提示：还有下一页时静默，没有下一页且列表非空时展示「前 N 本」尾注。 */
    private void refreshPaginationHint() {
        if (loadingMore == null) {
            return;
        }
        if (level != LEVEL_BOOKS || bookGrid == null
                || bookGrid.getVisibility() != View.VISIBLE
                || bookAdapter == null || bookAdapter.getCount() <= 0) {
            Ui.display(loadingMore, false);
            return;
        }
        if (hasMore) {
            Ui.display(loadingMore, false);
            return;
        }
        loadingMore.setText(getContext().getString(R.string.category_end_hint,
                bookAdapter.getCount()));
        Ui.display(loadingMore, true);
    }

    /** 切换语言时的过渡：保留旧书单、半透明并禁止滚动/点击，避免骨架屏闪烁。 */
    private void startContentTransition() {
        if (bookGrid == null) {
            return;
        }
        bookGrid.setAlpha(0.4f);
        bookGrid.setEnabled(false);
        bookGrid.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });
        if (loadingMore != null) {
            loadingMore.setText(R.string.category_switching_language);
            Ui.display(loadingMore, true);
        }
    }

    /** 结束过渡：恢复不透明度、可点击/可滚动状态并移除触摸拦截。 */
    private void endContentTransition() {
        if (bookGrid == null) {
            return;
        }
        bookGrid.setAlpha(1.0f);
        bookGrid.setEnabled(true);
        bookGrid.setOnTouchListener(null);
    }

    // =====================================================================
    // 书籍详情：可靠映射进原生详情，否则回退网页详情
    // =====================================================================

    private void openBook(int position) {
        final Book book = bookAdapter == null ? null : bookAdapter.getItem(position);
        final Activity activity = hostActivity();
        if (book == null || activity == null || activity.isFinishing()) {
            return;
        }
        if (book.hash != null && book.hash.length() > 0) {
            BookDetailActivity.open(activity, book);
            return;
        }
        // 映射缓存命中：跳过二次网络检索/详情请求，直接打开原生详情。
        final String cacheKey = BookMapper.cacheKey(book);
        Book cached = cacheKey.length() == 0 ? null : MAPPING_CACHE.get(cacheKey);
        if (cached != null) {
            BookDetailActivity.open(activity, cached);
            return;
        }
        if (mappingDialog != null && mappingDialog.isShowing()) {
            return;
        }
        final int token = ++mappingToken;
        showMappingDialog(activity, token);
        Task.run(new Task.Job<BookMapper.Result>() {
            @Override
            public BookMapper.Result run() throws Exception {
                return BookMapper.mapToNative(book);
            }
        }, new Task.Ok<BookMapper.Result>() {
            @Override
            public void ok(BookMapper.Result result) {
                if (token != mappingToken) {
                    // 用户已取消/已发起新的映射请求：丢弃这次回调。
                    return;
                }
                dismissMappingDialog();
                Activity host = hostActivity();
                if (host == null || host.isFinishing()) {
                    return;
                }
                if (result != null && result.book != null) {
                    if (cacheKey.length() > 0) {
                        MAPPING_CACHE.put(cacheKey, result.book);
                    }
                    BookDetailActivity.open(host, result.book);
                } else {
                    showWebDetailDialog(book);
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != mappingToken) {
                    return;
                }
                dismissMappingDialog();
                showWebDetailDialog(book);
            }
        });
    }

    /** 显式映射加载弹窗（可取消）：替代原来静默 Toast + 忽略后续点击的交互。 */
    private void showMappingDialog(Activity activity, final int token) {
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setMessage(R.string.category_mapping_dialog_message)
                    .setNegativeButton(R.string.action_cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            cancelMapping(token);
                        }
                    })
                    .create();
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface d) {
                    cancelMapping(token);
                }
            });
            dialog.show();
            mappingDialog = dialog;
        } catch (Throwable ignored) {
            // 弹窗失败时继续做映射，行为退化为无提示加载。
        }
    }

    /** 取消映射：递增 token 丢弃在途回调并恢复书单可点击状态。 */
    private void cancelMapping(int token) {
        if (token == mappingToken) {
            mappingToken++;
        }
        dismissMappingDialog();
    }

    private void dismissMappingDialog() {
        AlertDialog dialog = mappingDialog;
        mappingDialog = null;
        if (dialog != null) {
            try {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (Throwable ignored) {
                // 弹窗已随 Activity 销毁时忽略。
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissMappingDialog();
        super.onDetachedFromWindow();
    }

    /** 无法可靠映射时：允许打开该书网页详情（不把不同版本/语言强行合并）。 */
    private void showWebDetailDialog(final Book book) {
        final Activity activity = hostActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final String url = book == null || book.webUrl == null ? "" : book.webUrl.trim();
        if (url.length() == 0) {
            Ui.toast(getContext(), R.string.category_detail_unavailable);
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.category_web_detail_title)
                    .setMessage(R.string.category_web_detail_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.category_open_web, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openExternal(activity, url);
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
            // 弹窗失败时退化为直接打开。
            openExternal(activity, url);
        }
    }

    private static void openExternal(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable t) {
            Ui.toast(activity, R.string.category_no_browser);
        }
    }

    // =====================================================================
    // 头部 / 语言筛选
    // =====================================================================

    private void updateHeader() {
        if (titleView == null) {
            return;
        }
        boolean showBreadcrumb = level != LEVEL_ROOT;
        Ui.display(breadcrumbBar, showBreadcrumb);
        Ui.display(titleView, showBreadcrumb);
        if (showBreadcrumb) {
            Ui.display(backRootButton, true);
            boolean showParent = level == LEVEL_BOOKS && parent != null;
            Ui.display(backParentButton, showParent);
            if (showParent) {
                backParentButton.setText(getContext().getString(
                        R.string.category_back_parent, parent.title()));
                backParentButton.setContentDescription(getContext().getString(
                        R.string.category_back_parent_desc) + "：" + parent.title());
            }
        }
        Ui.display(languageBar, level == LEVEL_BOOKS);

        if (level == LEVEL_ROOT) {
            titleView.setText(R.string.category_root_title);
            if (roots.isEmpty()) {
                // 分类树还没加载完：不显示「共 0 个一级分类」的中间值。
                Ui.display(subtitleView, false);
            } else {
                Ui.display(subtitleView, true);
                subtitleView.setText(getContext().getString(
                        R.string.category_root_subtitle, roots.size()));
            }
        } else if (level == LEVEL_CHILDREN) {
            Ui.display(subtitleView, true);
            titleView.setText(parent == null ? "" : parent.title());
            if (parent != null && parent.hasChineseName()) {
                subtitleView.setText(getContext().getString(R.string.category_children_subtitle,
                        parent.sourceName, parent.children.size()));
            } else if (parent != null) {
                subtitleView.setText(getContext().getString(R.string.category_children_subtitle_plain,
                        parent.children.size()));
            }
        } else {
            StringBuilder title = new StringBuilder();
            if (category != null && parent != null && category == parent) {
                // 从「浏览全部 · 一级分类」进入：标题显示「一级分类 · 全部」，与 L1 首行呼应。
                title.append(getContext().getString(R.string.category_all_title, parent.title()));
            } else {
                if (parent != null) {
                    title.append(parent.title()).append(" / ");
                }
                title.append(category == null ? "" : category.title());
            }
            titleView.setText(title.toString());
            StringBuilder sub = new StringBuilder();
            if (page > 0) {
                sub.append(getContext().getString(R.string.category_books_page_status,
                        page, Math.max(totalPages, page), bookAdapter.getCount()));
                if (category != null && category.hasChineseName()) {
                    sub.append(" · ").append(category.sourceName);
                }
            } else {
                sub.append(getContext().getString(R.string.category_loading_books));
            }
            subtitleView.setText(sub.toString());
        }
        syncChrome();
    }

    /** 一次性创建 9 个语言 Chip（外层 48dp 热区 + 内层 32dp 胶囊），后续只更新状态。 */
    private void createLanguageChips() {
        if (languageRow == null) {
            return;
        }
        languageRow.removeAllViews();
        languageChips.clear();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (final String value : LANGUAGE_VALUES) {
            View touch = inflater.inflate(R.layout.row_language_chip, languageRow, false);
            TextView label = (TextView) touch.findViewById(R.id.tv_language_chip);
            if (label != null) {
                label.setText(CategoryNames.languageDisplayName(value));
            }
            touch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectLanguage(value);
                }
            });
            LanguageChip chip = new LanguageChip();
            chip.value = value;
            chip.touch = touch;
            chip.label = label;
            languageChips.add(chip);
            languageRow.addView(touch);
        }
        updateLanguageChips();
    }

    /** 只遍历更新选中态/描边背景（state_selected）/字重，不 removeAllViews。 */
    private void updateLanguageChips() {
        for (LanguageChip chip : languageChips) {
            boolean selected = chip.value.equals(language);
            chip.touch.setSelected(selected);
            if (chip.label != null) {
                chip.label.setSelected(selected);
                chip.label.setTypeface(Typeface.DEFAULT,
                        selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            chip.touch.setContentDescription(CategoryNames.languageDisplayName(chip.value)
                    + (selected ? getContext().getString(R.string.selected_suffix) : ""));
        }
    }

    /** 选中 Chip 自动滚动到可视区中央（smoothScrollTo）。 */
    private void scrollSelectedLanguageToCenter(final String value) {
        if (languageBar == null || languageRow == null) {
            return;
        }
        languageBar.post(new Runnable() {
            @Override
            public void run() {
                for (LanguageChip chip : languageChips) {
                    if (!chip.value.equals(value)) {
                        continue;
                    }
                    int paddingLeft = languageBar.getPaddingLeft();
                    int contentWidth = paddingLeft + languageRow.getWidth()
                            + languageBar.getPaddingRight();
                    int center = paddingLeft + chip.touch.getLeft() + chip.touch.getWidth() / 2;
                    int target = center - languageBar.getWidth() / 2;
                    int max = Math.max(0, contentWidth - languageBar.getWidth());
                    if (target < 0) {
                        target = 0;
                    }
                    if (target > max) {
                        target = max;
                    }
                    languageBar.smoothScrollTo(target, 0);
                    return;
                }
            }
        });
    }

    // =====================================================================
    // 重试 / 服务器故障切换
    // =====================================================================

    /**
     * 重试：与书库页一致，上一次失败像是服务器侧问题（网络/验证/页面改版）时，
     * 先自动挑一个可用的备用镜像并切换，再重新请求；分类不存在则直接重试当前站点。
     */
    private void retryWithFailover() {
        if (loading) {
            return;
        }
        if (!lastFailureServerSide) {
            retryCurrent();
            return;
        }
        if (errorRetry != null) {
            errorRetry.setEnabled(false);
        }
        if (errorDesc != null) {
            errorDesc.setText(R.string.switching_server);
        }
        Task.run(new Task.Job<String>() {
            @Override
            public String run() throws Exception {
                return Hosts.pickNext(Api.get().base());
            }
        }, new Task.Ok<String>() {
            @Override
            public void ok(String base) {
                if (errorRetry != null) {
                    errorRetry.setEnabled(true);
                }
                if (base != null && base.length() > 0) {
                    try {
                        Api.get().setBase(base);
                        // 分类书单请求用的是首次拉树时的 baseUrl，换镜像后一并对齐到新站点。
                        baseUrl = base;
                        Ui.toast(getContext(), getResources().getString(
                                R.string.server_switched, Hosts.hostOf(base)));
                    } catch (Throwable ignored) {
                        // 切换失败也继续用当前服务器重试一次。
                    }
                }
                retryCurrent();
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (errorRetry != null) {
                    errorRetry.setEnabled(true);
                }
                retryCurrent();
            }
        });
    }

    /** 重试当前层级（L0 分类目录 / L2 书单）。 */
    private void retryCurrent() {
        if (level == LEVEL_BOOKS) {
            loadBooks(1, true);
        } else {
            loadTree();
        }
    }

    /** 分类不存在是数据层结论（换镜像无用）；网络失败/网页验证/页面结构变化值得先换镜像。 */
    private static boolean isServerSideFailure(Throwable error) {
        if (!(error instanceof WebCategorySource.SourceException)) {
            return true;
        }
        int kind = ((WebCategorySource.SourceException) error).kind;
        return kind != WebCategorySource.ERROR_NOT_FOUND;
    }

    // =====================================================================
    // 状态
    // =====================================================================

    /** 按当前层级选择骨架形态：L0 列表骨架；L2 网格骨架（与真实书封网格同构）。 */
    private void showSkeletonStateForLevel() {
        Ui.display(categoryList, false);
        Ui.display(bookGrid, false);
        Ui.display(skeleton, true);
        boolean gridSkeleton = level == LEVEL_BOOKS;
        Ui.display(skeletonList, !gridSkeleton);
        Ui.display(skeletonGrid, gridSkeleton);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
        Ui.display(loadingMore, false);
    }

    private void showListState() {
        endContentTransition();
        Ui.display(categoryList, true);
        Ui.display(bookGrid, false);
        Ui.display(skeleton, false);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
        Ui.display(loadingMore, false);
    }

    private void showContentState() {
        Ui.display(categoryList, false);
        Ui.display(bookGrid, true);
        Ui.display(skeleton, false);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
    }

    private void showEmptyState() {
        Ui.display(categoryList, false);
        Ui.display(bookGrid, false);
        Ui.display(skeleton, false);
        Ui.display(errorState, false);
        Ui.display(loadingMore, false);
        if (emptyTitle != null) {
            emptyTitle.setText(R.string.category_empty_title);
        }
        if (emptyDesc != null) {
            emptyDesc.setText(language.length() > 0
                    ? R.string.category_empty_desc : R.string.category_empty_desc_plain);
        }
        if (emptyAction != null) {
            emptyAction.setText(language.length() > 0
                    ? R.string.category_empty_clear_language : R.string.action_refresh);
            Ui.display(emptyAction, true);
        }
        Ui.display(emptyState, true);
    }

    private void showErrorState(String message) {
        Ui.display(categoryList, false);
        Ui.display(bookGrid, false);
        Ui.display(skeleton, false);
        Ui.display(emptyState, false);
        if (errorTitle != null) {
            errorTitle.setText(R.string.category_error_title);
        }
        if (errorDesc != null) {
            errorDesc.setText(message == null || message.length() == 0
                    ? getContext().getString(R.string.category_error_unknown) : message);
        }
        if (errorRetry != null) {
            errorRetry.setText(R.string.action_retry);
            errorRetry.setEnabled(true);
        }
        Ui.display(errorState, true);
        Ui.display(loadingMore, false);
    }

    /** 把数据源异常翻译成面向用户的分类状态文案（不展示接口名/JSON/堆栈）。 */
    private String categoryErrorMessage(Exception e) {
        if (e instanceof WebCategorySource.SourceException) {
            int kind = ((WebCategorySource.SourceException) e).kind;
            if (kind == WebCategorySource.ERROR_CHALLENGE) {
                return getContext().getString(R.string.category_error_challenge);
            }
            if (kind == WebCategorySource.ERROR_NOT_FOUND) {
                return getContext().getString(R.string.category_error_not_found);
            }
            if (kind == WebCategorySource.ERROR_PARSE) {
                return getContext().getString(R.string.category_error_parse);
            }
            return getContext().getString(R.string.category_error_network);
        }
        // 非分类数据源的底层异常：只归入「网络失败 / 其它」两类，不把英文异常/HTTP 状态透给用户。
        if (e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof javax.net.ssl.SSLException) {
            return getContext().getString(R.string.category_error_network);
        }
        return getContext().getString(R.string.category_error_unknown);
    }

    /**
     * 作废所有在途的分类请求（返回上一级/跨级返回时调用）。
     *
     * 旧版只在发起新请求时递增 token；如果 L2 书单请求在途时用户点面包屑回到 L1/L0，
     * 旧回调的 token 仍然有效，会把书单/空态/错误态覆盖到分类目录列表上。
     */
    private void invalidatePendingRequests() {
        requestToken++;
        loading = false;
        endContentTransition();
        Ui.display(loadingMore, false);
    }

    // =====================================================================
    // 滚动位置记忆
    // =====================================================================

    private void captureCategoryScroll(int atLevel) {
        if (categoryList == null) {
            return;
        }
        int index = categoryList.getFirstVisiblePosition();
        View first = categoryList.getChildAt(0);
        int top = first == null ? 0 : first.getTop();
        if (atLevel == LEVEL_ROOT) {
            l0ScrollIndex = index;
            l0ScrollTop = top;
        } else {
            l1ScrollIndex = index;
            l1ScrollTop = top;
        }
    }

    private void restoreCategoryScroll(final int index, final int top) {
        if (categoryList == null) {
            return;
        }
        // 直接恢复一次，再在下一帧兜底一次（首次显示列表时布局尚未完成）。
        categoryList.setSelectionFromTop(index, top);
        categoryList.post(new Runnable() {
            @Override
            public void run() {
                categoryList.setSelectionFromTop(index, top);
            }
        });
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private Activity hostActivity() {
        Context context = getContext();
        return (context instanceof Activity) ? (Activity) context : null;
    }

    private String readableType(String type) {
        if (type == null || type.length() == 0) {
            return "";
        }
        if ("fiction".equalsIgnoreCase(type)) {
            return getContext().getString(R.string.category_type_fiction);
        }
        if ("non-fiction".equalsIgnoreCase(type)) {
            return getContext().getString(R.string.category_type_non_fiction);
        }
        return type;
    }

    /** 语言胶囊 View 句柄（预创建后只更新状态）。 */
    private static final class LanguageChip {
        String value = "";
        View touch;
        TextView label;
    }

    /** 分类列表行模型。 */
    private static final class Row {
        Category category;
        boolean all;
        String title = "";
        String subtitle = "";
        String count = "";
    }

    /** 一级/二级分类列表适配器。 */
    private static final class CategoryRowAdapter extends BaseAdapter {

        private final LayoutInflater inflater;
        private final List<Row> rows;

        CategoryRowAdapter(Context context, List<Row> rows) {
            this.inflater = LayoutInflater.from(context);
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.row_category, parent, false);
            }
            Row row = rows.get(position);
            TextView title = (TextView) view.findViewById(R.id.tv_row_category_title);
            TextView subtitle = (TextView) view.findViewById(R.id.tv_row_category_subtitle);
            TextView count = (TextView) view.findViewById(R.id.tv_row_category_count);
            if (title != null) {
                title.setText(row.title == null ? "" : row.title);
            }
            if (subtitle != null) {
                if (row.subtitle == null || row.subtitle.length() == 0) {
                    Ui.display(subtitle, false);
                } else {
                    Ui.display(subtitle, true);
                    subtitle.setText(row.subtitle);
                }
            }
            if (count != null) {
                if (row.count == null || row.count.length() == 0) {
                    Ui.display(count, false);
                } else {
                    Ui.display(count, true);
                    count.setText(row.count);
                }
            }
            return view;
        }
    }
}
