package com.zlib.clone.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.data.Task;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.Page;
import com.zlib.clone.net.Api;
import com.zlib.clone.net.ApiException;
import com.zlib.clone.net.Hosts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 书库页：标题 + 圆角搜索框 + 榜单分段栏 + 书封网格。
 *
 * 关键行为：
 * - 分段栏「热门 / 分类 / 猜你喜欢」：热门→Api.mostPopular、猜你喜欢→Api.recommended；
 *   分类→网页分类目录（WebCategorySource，见 CategoryView），不再使用「书源暂未提供内容分类」的降级页；
 * - 搜索态隐藏分段栏，清空搜索后恢复进入搜索前那个榜单的列表与滚动位置（不重新请求）；
 * - 首屏/重置加载显示骨架，另有榜单空、搜索无结果、请求失败三种状态；
 * - 滚动分页，旧响应（requestToken 不一致）一律丢弃，避免覆盖新结果；
 * - 点击卡片进入 BookDetailActivity；handleBack() 供 MainActivity 返回键处理搜索态。
 */
public class LibraryView extends FrameLayout implements CategoryView.ChromeHost {

    private static final int RANK_POPULAR = 0;
    private static final int RANK_CATEGORIES = 1;
    private static final int RANK_LIKED = 2;

    /** 宽度达到该 dp 时网格用 3 列，否则 2 列。 */
    private static final int WIDE_SCREEN_DP = 600;
    private static final int COLUMNS_PHONE = 2;
    private static final int COLUMNS_WIDE = 3;
    /** 分页触底阈值：最后可见项 + 4 >= 总数时加载下一页。 */
    private static final int PAGINATION_THRESHOLD = 4;

    private TextView libraryTitle;
    private LinearLayout searchBox;
    private EditText searchInput;
    private ImageButton clearButton;
    private TextView submitButton;
    private LinearLayout segmentsBar;
    private TextView segPopular;
    private TextView segCategories;
    private TextView segLiked;
    private GridView grid;
    private LinearLayout skeleton;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView emptyAction;
    private LinearLayout errorState;
    private TextView errorTitle;
    private TextView errorDesc;
    private TextView errorRetry;

    /** 最近一次失败是否值得换服务器重试（网络/重定向/5xx 等；401/403/404 不换）。 */
    private boolean lastFailureServerSide;
    private TextView loadingMore;


    private BookAdapter adapter;
    /** 网页分类目录视图：与榜单/搜索结果共用 fl_library_content，切到「分类」分段时显示。 */
    private CategoryView categoryView;
    /** 紧凑顶栏：分类视图钻取到 L1/L2 时隐藏书库标题与全库搜索框，只保留 36dp 分段栏。 */
    private boolean chromeCompact = false;

    /** 数据/加载状态。 */
    private int rankMode = RANK_POPULAR;
    private boolean searchMode = false;
    private String query = "";
    private int page = 0;
    private boolean hasMore = true;
    private boolean loading = false;
    /** 旧响应保护：每次发起请求 token = ++requestToken，回调 token 不一致就丢弃。 */
    private int requestToken = 0;
    private boolean started = false;
    private boolean rankLoaded = false;
    private boolean rankError = false;
    /** 当前 Adapter 里缓存的是哪个榜单的数据（用于从分类切回热门时直接复用、不重刷接口）。 */
    private int cachedRankMode = -1;
    /** 离开某个榜单时记下的网格滚动位置。 */
    private int rankScrollMode = -1;
    private int rankScrollIndex = 0;
    private int rankScrollTop = 0;

    /** 进入搜索前的榜单快照：清空搜索时原样恢复，不重新请求。 */
    private int savedRankMode = -1;
    private List<Book> savedRankItems = new ArrayList<Book>();
    private int savedRankScroll = 0;
    private int savedRankPage = 0;
    private boolean savedRankHasMore = true;
    private boolean savedRankLoaded = false;
    private boolean savedRankError = false;
    private String savedRankErrorText = "";

    public LibraryView(Context context) {
        super(context);
        init(context);
    }

    public LibraryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    // =====================================================================
    // 初始化
    // =====================================================================

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_library, this, true);

        libraryTitle = (TextView) findViewById(R.id.tv_library_title);
        searchBox = (LinearLayout) findViewById(R.id.ll_library_search_box);
        searchInput = (EditText) findViewById(R.id.et_library_search);
        clearButton = (ImageButton) findViewById(R.id.btn_library_clear);
        submitButton = (TextView) findViewById(R.id.btn_library_search);
        segmentsBar = (LinearLayout) findViewById(R.id.ll_library_segments);
        segPopular = (TextView) findViewById(R.id.tv_seg_popular);
        segCategories = (TextView) findViewById(R.id.tv_seg_categories);
        segLiked = (TextView) findViewById(R.id.tv_seg_liked);
        grid = (GridView) findViewById(R.id.gv_library);
        skeleton = (LinearLayout) findViewById(R.id.ll_library_skeleton);
        emptyState = (LinearLayout) findViewById(R.id.ll_library_empty);
        emptyTitle = (TextView) findViewById(R.id.tv_library_empty_title);
        emptyDesc = (TextView) findViewById(R.id.tv_library_empty_desc);
        emptyAction = (TextView) findViewById(R.id.btn_library_empty_action);
        errorState = (LinearLayout) findViewById(R.id.ll_library_error);
        errorTitle = (TextView) findViewById(R.id.tv_library_error_title);
        errorDesc = (TextView) findViewById(R.id.tv_library_error_desc);
        errorRetry = (TextView) findViewById(R.id.btn_library_error_retry);
        loadingMore = (TextView) findViewById(R.id.tv_library_loading_more);


        adapter = new BookAdapter(context);
        // 分类视图挂在书库内容区里，默认隐藏；「分类」分段被点时显示（状态由 CategoryView 自己维护）。
        ViewGroup contentContainer = (ViewGroup) findViewById(R.id.fl_library_content);
        if (contentContainer != null) {
            categoryView = new CategoryView(context, this);
            contentContainer.addView(categoryView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            Ui.display(categoryView, false);
        }
        int columns = resolveColumns(context);
        if (grid != null) {
            grid.setNumColumns(columns);
            grid.setAdapter(adapter);
            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    openBook(position);
                }
            });
            grid.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    // 不需要处理滚动状态。
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem,
                                     int visibleItemCount, int totalItemCount) {
                    if (loading || !hasMore || totalItemCount <= 0) {
                        return;
                    }
                    if (firstVisibleItem + visibleItemCount + PAGINATION_THRESHOLD >= totalItemCount) {
                        loadPage(page + 1, false);
                    }
                }
            });
        }

        if (submitButton != null) {
            submitButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitSearch();
                }
            });
        }
        if (clearButton != null) {
            clearButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClearClicked();
                }
            });
        }
        if (segPopular != null) {
            segPopular.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchRank(RANK_POPULAR);
                }
            });
        }
        if (segCategories != null) {
            segCategories.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchRank(RANK_CATEGORIES);
                }
            });
        }
        if (segLiked != null) {
            segLiked.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchRank(RANK_LIKED);
                }
            });
        }
        if (searchInput != null) {
            searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    boolean submit = (actionId == EditorInfo.IME_ACTION_SEARCH)
                            || (actionId == EditorInfo.IME_ACTION_DONE);
                    if (!submit && event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        submit = true;
                    }
                    if (!submit) {
                        return false;
                    }
                    // 硬件回车会同时收到 ACTION_DOWN/UP，只处理 DOWN。
                    if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                        submitSearch();
                    }
                    return true;
                }
            });
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    // 不需要处理。
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // 不需要处理。
                }

                @Override
                public void afterTextChanged(Editable s) {
                    updateClearButton();
                }
            });
        }
        if (emptyAction != null) {
            emptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (searchMode) {
                        clearSearch();
                    } else {
                        loadPage(1, true);
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

        updateSegmentsVisibility();
        updateSegments();
        updateClearButton();
    }

    /** 宽屏（>=600dp）用 3 列，手机 2 列。 */
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
    // 对外入口（MainActivity 调用）
    // =====================================================================

    /** MainActivity 每次切到书库 tab 都会调用；只在首次进入时加载榜单。 */
    public void onShown() {
        if (started) {
            return;
        }
        started = true;
        loadPage(1, true);
    }

    /** 返回键：搜索态返回 true 并清空搜索（恢复原榜单），否则返回 false。 */
    public boolean handleBack() {
        if (searchMode) {
            clearSearch();
            return true;
        }
        if (rankMode == RANK_CATEGORIES) {
            // 分类页内：先按层级返回（书单→二级→一级），已在目录层则回到热门。
            if (categoryView != null && categoryView.handleBack()) {
                return true;
            }
            switchRank(RANK_POPULAR);
            return true;
        }
        return false;
    }

    // =====================================================================
    // 搜索
    // =====================================================================

    private void submitSearch() {
        if (searchInput == null) {
            return;
        }
        String text = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (text.length() == 0) {
            // 空关键词不发起搜索；已在搜索态则视为清空。
            if (searchMode) {
                clearSearch();
            }
            return;
        }
        if (!searchMode) {
            saveRankSnapshot();
        }
        searchMode = true;
        query = text;
        started = true;
        setChromeCompact(false);
        updateSegmentsVisibility();
        updateSegments();
        hideKeyboard();
        loadPage(1, true);
    }

    private void onClearClicked() {
        if (searchInput != null) {
            searchInput.setText("");
        }
        updateClearButton();
        if (searchMode) {
            clearSearch();
        }
    }

    /** 清空搜索：恢复分段栏 + 上次榜单的列表数据与滚动位置（不重新请求）。 */
    private void clearSearch() {
        if (!searchMode) {
            if (searchInput != null) {
                searchInput.setText("");
            }
            updateClearButton();
            return;
        }

        // 递增 token：在途搜索响应作废；同时结束 loading 以免卡死后续分页。
        requestToken++;
        loading = false;
        searchMode = false;
        query = "";
        Ui.display(loadingMore, false);
        hideKeyboard();
        if (searchInput != null) {
            searchInput.setText("");
        }
        updateClearButton();

        int restoreMode = savedRankMode;
        List<Book> restoreItems = savedRankItems;
        int restoreScroll = savedRankScroll;
        int restorePage = savedRankPage;
        boolean restoreHasMore = savedRankHasMore;
        boolean restoreLoaded = savedRankLoaded;
        boolean restoreError = savedRankError;
        String restoreErrorText = savedRankErrorText;
        clearSavedSnapshot();

        if (restoreMode >= 0) {
            rankMode = restoreMode;
        }
        updateSegmentsVisibility();
        updateSegments();

        if (restoreMode == RANK_CATEGORIES) {
            // 进搜索前在「分类」：恢复分类视图本身的层级/滚动状态，不发分类请求也不显示榜单网格。
            if (adapter != null) {
                adapter.setItems(restoreItems);
            }
            page = restorePage;
            hasMore = restoreHasMore;
            rankLoaded = restoreLoaded;
            rankError = restoreError;
            showCategoriesMode();
            return;
        }

        if (!restoreLoaded) {
            // 进搜索前榜单还没成功加载过（例如首屏还在加载），没有数据可恢复，重新请求。
            loadPage(1, true);
            return;
        }

        if (adapter != null) {
            adapter.setItems(restoreItems);
        }
        page = restorePage;
        hasMore = restoreHasMore;
        rankLoaded = true;
        rankError = restoreError;
        if (restoreError) {
            showErrorState(restoreErrorText);
        } else if (adapter == null || adapter.getCount() == 0) {
            showRankEmptyState();
        } else {
            showContentState();
        }

        final int scrollTo = restoreScroll;
        if (grid != null) {
            grid.post(new Runnable() {
                @Override
                public void run() {
                    if (!searchMode) {
                        grid.setSelection(scrollTo);
                    }
                }
            });
        }
    }

    private void saveRankSnapshot() {
        savedRankMode = rankMode;
        savedRankItems = (adapter == null) ? new ArrayList<Book>() : adapter.getItems();
        savedRankScroll = (grid == null) ? 0 : grid.getFirstVisiblePosition();
        savedRankPage = page;
        savedRankHasMore = hasMore;
        savedRankLoaded = rankLoaded;
        savedRankError = rankError;
        savedRankErrorText = (errorDesc == null) ? "" : errorDesc.getText().toString();
    }

    private void clearSavedSnapshot() {
        savedRankMode = -1;
        savedRankItems = new ArrayList<Book>();
        savedRankScroll = 0;
        savedRankPage = 0;
        savedRankHasMore = true;
        savedRankLoaded = false;
        savedRankError = false;
        savedRankErrorText = "";
    }

    // =====================================================================
    // 榜单切换
    // =====================================================================

    private void switchRank(int rank) {
        if (!searchMode && rank == rankMode) {
            return;
        }
        if (searchMode) {
            // 分段栏在搜索态是隐藏的，这里只做防御性恢复，正常不会走到。
            searchMode = false;
            clearSavedSnapshot();
        }
        int previousRank = rankMode;
        if (previousRank != RANK_CATEGORIES && rank != previousRank) {
            rememberRankScroll(previousRank);
        }
        rankMode = rank;
        started = true;
        updateSegmentsVisibility();
        updateSegments();
        if (rank == RANK_CATEGORIES) {
            // 分类：显示网页分类目录视图（一级分类 → 二级分类 → 分类书单），不再发 /eapi 榜单请求。
            showCategoriesMode();
            return;
        }
        Ui.display(categoryView, false);
        setChromeCompact(false);
        if (rank == cachedRankMode && rankLoaded && adapter != null && adapter.getCount() > 0) {
            // 回到已有缓存的榜单（如「分类 → 热门」）：只切换视图显隐并恢复列表/滚动位置，
            // 不再调用 loadPage(1, true) 清空重刷，避免重复请求接口与丢失浏览位置。
            requestToken++;
            loading = false;
            Ui.display(loadingMore, false);
            showContentState();
            restoreRankScroll(rank);
            return;
        }
        loadPage(1, true);
    }

    /** 记录当前榜单网格的滚动位置（切走/进分类前调用）。 */
    private void rememberRankScroll(int mode) {
        rankScrollMode = mode;
        if (grid == null) {
            return;
        }
        rankScrollIndex = grid.getFirstVisiblePosition();
        View child = grid.getChildAt(0);
        rankScrollTop = child == null ? 0 : child.getTop();
    }

    /** 恢复对应榜单的滚动位置（仅在列表已恢复时调用）。 */
    private void restoreRankScroll(final int mode) {
        if (grid == null || rankScrollMode != mode) {
            return;
        }
        final int index = rankScrollIndex;
        final int top = rankScrollTop;
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (!searchMode && rankMode == mode) {
                    grid.setSelectionFromTop(index, top);
                }
            }
        });
    }

    /** 显示分类视图并把书库网格/各状态隐藏（分类页自己管理骨架/空/失败状态）。 */
    private void showCategoriesMode() {
        requestToken++;
        loading = false;
        Ui.display(loadingMore, false);
        // 不清理 adapter：保留热门/猜你喜欢的缓存数据，从分类切回热门时直接恢复、不重复请求。
        Ui.display(grid, false);
        Ui.display(skeleton, false);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
        Ui.display(categoryView, true);
        if (categoryView != null) {
            // 分类视图自身保留层级与滚动位置；重新显示时把 L0/L1/L2 对应的顶栏状态同步给书库页。
            categoryView.onShown();
        }
    }

    private void updateSegmentsVisibility() {
        Ui.display(segmentsBar, !searchMode);
    }

    private void updateSegments() {
        if (segPopular != null) {
            segPopular.setSelected(rankMode == RANK_POPULAR);
        }
        if (segCategories != null) {
            segCategories.setSelected(rankMode == RANK_CATEGORIES);
        }
        if (segLiked != null) {
            segLiked.setSelected(rankMode == RANK_LIKED);
        }
    }

    /**
     * 分类视图钻取联动（L0 → 完整顶栏；L1/L2 → 紧凑顶栏）。
     *
     * @param compact true = 隐藏书库 26sp 标题与全库搜索框，仅保留 36dp 分段栏，
     *                释放垂直空间并避免用户在分类内误用全库搜索
     */
    @Override
    public void setChromeCompact(boolean compact) {
        if (chromeCompact == compact) {
            return;
        }
        chromeCompact = compact;
        Ui.display(libraryTitle, !compact);
        Ui.display(searchBox, !compact);
        if (compact) {
            hideKeyboard();
        }
    }

    // =====================================================================
    // 请求
    // =====================================================================

    /**
     * 请求一页数据。
     *
     * @param targetPage 目标页（从 1 开始）
     * @param reset      是否重置列表（切榜/搜索/重试为 true，分页为 false）
     */
    private void loadPage(final int targetPage, final boolean reset) {
        final int token = ++requestToken;
        final boolean searchRequest = searchMode;
        final String requestQuery = query;
        final int requestMode = rankMode;
        final int requestPage = targetPage < 1 ? 1 : targetPage;

        if (!searchRequest && requestMode == RANK_CATEGORIES) {
            // 从搜索态清空、回到「分类」分段：直接显示已保留的分类视图状态，不发书籍请求。
            loading = false;
            showCategoriesMode();
            return;
        }
        Ui.display(categoryView, false);

        loading = true;
        if (reset) {
            page = 0;
            hasMore = true;
            if (adapter != null) {
                adapter.clear();
            }
            if (grid != null) {
                grid.setSelection(0);
            }
            if (!searchRequest) {
                // 新榜单尚未成功加载，清空搜索时应重新请求而不是恢复旧状态。
                rankLoaded = false;
                rankError = false;
            }
            Ui.display(loadingMore, false);
            showSkeletonState();
        } else {
            Ui.display(loadingMore, true);
        }

        Task.run(new Task.Job<Page<Book>>() {
            @Override
            public Page<Book> run() throws Exception {
                Api api = Api.get();
                if (searchRequest) {
                    return api.search(requestQuery, requestPage);
                }
                if (requestMode == RANK_LIKED) {
                    return api.recommended(requestPage);
                }
                return api.mostPopular(requestPage);
            }
        }, new Task.Ok<Page<Book>>() {
            @Override
            public void ok(Page<Book> result) {
                if (token != requestToken) {
                    return; // 旧响应：已有更新的请求，直接丢弃。
                }
                loading = false;
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                Ui.display(skeleton, false);
                Ui.display(loadingMore, false);

                Page<Book> value = (result == null) ? new Page<Book>() : result;
                page = value.current > 0 ? value.current : requestPage;
                hasMore = value.hasMore();
                List<Book> items = value.items;
                if (reset) {
                    if (adapter != null) {
                        adapter.setItems(items);
                    }
                } else if (items != null && !items.isEmpty() && adapter != null) {
                    adapter.addItems(items);
                }

                if (searchRequest) {
                    if (adapter == null || adapter.getCount() == 0) {
                        showSearchEmptyState();
                    } else {
                        showContentState();
                    }
                } else {
                    rankLoaded = true;
                    rankError = false;
                    cachedRankMode = requestMode;
                    if (adapter == null || adapter.getCount() == 0) {
                        showRankEmptyState();
                    } else {
                        showContentState();
                    }
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != requestToken) {
                    return; // 旧响应：丢弃。
                }
                loading = false;
                Activity activity = hostActivity();
                if (activity != null && activity.isFinishing()) {
                    return;
                }
                Ui.display(skeleton, false);
                Ui.display(loadingMore, false);

                // 401/403 优先走统一登录失效处理（内部会清登录态、跳登录页并结束当前页）。
                if (activity != null && Ui.handleAuthError(activity, e)) {
                    return;
                }

                lastFailureServerSide = isServerSideFailure(e);
                boolean firstPage = reset || adapter == null || adapter.getCount() == 0;
                if (firstPage) {
                    if (!searchRequest) {
                        rankError = true;
                        rankLoaded = false;
                    }
                    showErrorState(Ui.errorText(e));
                } else {
                    // 分页失败：保留已有列表，只提示，可继续滚动重试。
                    Ui.toast(getContext(), Ui.errorText(e));
                }
            }
        });
    }

    // =====================================================================
    // 重试 / 服务器故障切换
    // =====================================================================

    /**
     * 重试：若上一次失败像是服务器侧问题（网络中断、重定向环、5xx、反爬挑战等），
     * 先自动在其它内置镜像里挑一个可用的并切换，再重新加载；否则直接重试当前服务器。
     */
    private void retryWithFailover() {
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

    /** 重试当前书单/搜索。 */
    private void retryCurrent() {
        loadPage(1, true);
    }

    /** 401/403/404 属于业务/权限问题，换服务器没用；其余网络与服务器异常都值得切换。 */
    private static boolean isServerSideFailure(Throwable error) {
        if (!(error instanceof ApiException)) {
            return true;
        }
        int code = ((ApiException) error).httpCode;
        if (code == 401 || code == 403 || code == 404) {
            return false;
        }
        return true;
    }

    // =====================================================================
    // 状态切换
    // =====================================================================

    private void showSkeletonState() {
        Ui.display(grid, false);
        Ui.display(skeleton, true);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
        Ui.display(loadingMore, false);
    }

    private void showContentState() {
        Ui.display(grid, true);
        Ui.display(skeleton, false);
        Ui.display(emptyState, false);
        Ui.display(errorState, false);
        Ui.display(loadingMore, false);
    }

    /** 榜单空态：按钮「刷新」重新请求当前榜单。 */
    private void showRankEmptyState() {
        Ui.display(grid, false);
        if (emptyTitle != null) {
            emptyTitle.setText(R.string.library_empty_title);
        }
        if (emptyDesc != null) {
            emptyDesc.setText(R.string.library_empty_desc);
        }
        if (emptyAction != null) {
            emptyAction.setText(R.string.action_refresh);
        }
        Ui.display(emptyState, true);
        Ui.display(errorState, false);
        Ui.display(skeleton, false);
        Ui.display(loadingMore, false);
    }

    /** 搜索无结果态：按钮「清空搜索」回到原榜单。 */
    private void showSearchEmptyState() {
        Ui.display(grid, false);
        if (emptyTitle != null) {
            emptyTitle.setText(R.string.search_empty_title);
        }
        if (emptyDesc != null) {
            emptyDesc.setText(R.string.search_empty_desc);
        }
        if (emptyAction != null) {
            emptyAction.setText(R.string.search_no_result_action);
        }
        Ui.display(emptyState, true);
        Ui.display(errorState, false);
        Ui.display(skeleton, false);
        Ui.display(loadingMore, false);
    }

    /** 失败态：按钮「重试」重新请求当前榜单/搜索。 */
    private void showErrorState(String message) {
        Ui.display(grid, false);
        if (errorTitle != null) {
            errorTitle.setText(R.string.library_error_title);
        }
        if (errorDesc != null) {
            String text = (message == null || message.trim().length() == 0)
                    ? getContext().getString(R.string.error_network_desc)
                    : message;
            errorDesc.setText(text);
        }
        if (errorRetry != null) {
            errorRetry.setText(R.string.action_retry);
        }
        Ui.display(errorState, true);
        Ui.display(emptyState, false);
        Ui.display(skeleton, false);
        Ui.display(loadingMore, false);
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private void updateClearButton() {
        if (clearButton == null) {
            return;
        }
        boolean hasText = searchInput != null && searchInput.getText() != null
                && searchInput.getText().length() > 0;
        Ui.display(clearButton, hasText);
    }

    private void hideKeyboard() {
        if (searchInput == null) {
            return;
        }
        try {
            InputMethodManager manager = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
            // 输入法不可用时忽略。
        }
    }

    private Activity hostActivity() {
        Context context = getContext();
        return (context instanceof Activity) ? (Activity) context : null;
    }

    private void openBook(int position) {
        Book book = (adapter == null) ? null : adapter.getItem(position);
        Activity activity = hostActivity();
        if (book == null || activity == null || activity.isFinishing()) {
            return;
        }
        BookDetailActivity.open(activity, book);
    }
}
