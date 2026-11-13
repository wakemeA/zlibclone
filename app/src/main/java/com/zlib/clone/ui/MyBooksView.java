package com.zlib.clone.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.data.Task;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.Page;
import com.zlib.clone.net.Api;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的书籍：收藏 / 已下载 两个分段 + 卡片列表 + 更多菜单（取消收藏 / 删除下载记录）。
 *
 * 状态：首屏骨架 / 列表 / 空态（两种文案）/ 失败态 / 分页加载提示。
 * 旧响应保护：每次发起请求自增 requestToken，回调里的旧 token 直接丢弃。
 */
public class MyBooksView extends FrameLayout {

    private static final String MODE_SAVED = "saved";
    private static final String MODE_DOWNLOADED = "downloaded";
    /** 滚动到距离底部还剩几条时触发下一页。 */
    private static final int LOAD_MORE_THRESHOLD = 4;

    private TextView segmentSaved;
    private TextView segmentDownloaded;
    private ListView listView;
    private View skeletonView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private View errorView;
    private TextView errorDesc;
    private TextView loadingMore;
    private View loadMoreRetry;

    private BookListAdapter adapter;

    private String mode = MODE_SAVED;
    private int page;
    private boolean loading;
    private boolean hasMore = true;
    private boolean loadedOnce;
    private boolean loadFailed;
    /** 分页失败不代表末页；此标记仅暂停自动加载，等待用户显式重试。 */
    private boolean loadMoreFailed;
    private int requestToken;
    /** 从详情页收藏/下载后回到 Main 需要刷新书单。 */
    private boolean dirty = true;

    public MyBooksView(Context context) {
        super(context);
        init(context);
    }

    public MyBooksView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_mybooks, this, true);

        segmentSaved = (TextView) findViewById(R.id.tv_seg_saved);
        segmentDownloaded = (TextView) findViewById(R.id.tv_seg_downloaded);
        listView = (ListView) findViewById(R.id.lv_mybooks);
        skeletonView = findViewById(R.id.ll_mybooks_skeleton);
        emptyView = findViewById(R.id.ll_mybooks_empty);
        emptyTitle = (TextView) findViewById(R.id.tv_mybooks_empty_title);
        emptyDesc = (TextView) findViewById(R.id.tv_mybooks_empty_desc);
        errorView = findViewById(R.id.ll_mybooks_error);
        errorDesc = (TextView) findViewById(R.id.tv_mybooks_error_desc);
        loadingMore = (TextView) findViewById(R.id.tv_mybooks_loading_more);
        loadMoreRetry = findViewById(R.id.btn_mybooks_load_more_retry);
        loadMoreRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryLoadMore();
            }
        });

        adapter = new BookListAdapter(context, new BookListAdapter.OnMoreClickListener() {
            @Override
            public void onMore(Book book) {
                showItemMenu(book);
            }
        });
        adapter.setMode(mode);

        if (listView != null) {
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    Book book = adapter.getItem(position);
                    if (book == null) {
                        return;
                    }
                    Activity activity = activity();
                    if (activity != null && !activity.isFinishing()) {
                        BookDetailActivity.open(activity, book);
                    }
                }
            });
            listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    // 无需处理。
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                     int totalItemCount) {
                    if (loading || loadMoreFailed || !hasMore || totalItemCount <= 0) {
                        return;
                    }
                    if (firstVisibleItem + visibleItemCount + LOAD_MORE_THRESHOLD >= totalItemCount) {
                        loadPage(page + 1, false);
                    }
                }
            });
        }

        if (segmentSaved != null) {
            segmentSaved.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchMode(MODE_SAVED);
                }
            });
        }
        if (segmentDownloaded != null) {
            segmentDownloaded.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchMode(MODE_DOWNLOADED);
                }
            });
        }

        View emptyAction = findViewById(R.id.btn_mybooks_empty_action);
        if (emptyAction != null) {
            emptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Context context = getContext();
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).switchToLibraryTab();
                    }
                }
            });
        }
        View retry = findViewById(R.id.btn_mybooks_error_retry);
        if (retry != null) {
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadPage(1, true);
                }
            });
        }

        updateSegments();
        syncState();
    }

    /** MainActivity 切到「我的书籍」tab 时调用。 */
    public void onShown() {
        if (!loadedOnce || dirty) {
            dirty = false;
            loadedOnce = true;
            loadPage(1, true);
        }
    }

    /** 宿主在 onResume 时调用：标记书单需要刷新（详情页收藏/下载/删除后返回）。 */
    public void markDirty() {
        dirty = true;
    }

    private void switchMode(String newMode) {
        if (newMode == null || newMode.equals(mode)) {
            return;
        }
        mode = newMode;
        adapter.setMode(newMode);
        loadedOnce = true;
        updateSegments();
        loadPage(1, true);
    }

    private void updateSegments() {
        boolean saved = MODE_SAVED.equals(mode);
        if (segmentSaved != null) {
            segmentSaved.setSelected(saved);
        }
        if (segmentDownloaded != null) {
            segmentDownloaded.setSelected(!saved);
        }
    }

    private void retryLoadMore() {
        if (!loading && loadMoreFailed) {
            loadMoreFailed = false;
            loadPage(page + 1, false);
        }
    }

    private void loadPage(final int targetPage, final boolean reset) {
        if (!reset && (loading || loadMoreFailed || !hasMore)) {
            return;
        }
        final int token = ++requestToken;
        loading = true;
        if (reset) {
            page = 0;
            hasMore = true;
            loadFailed = false;
            loadMoreFailed = false;
            adapter.clear();
        }
        syncState();

        final String jobMode = mode;
        final int requestPage = targetPage < 1 ? 1 : targetPage;
        Task.run(new Task.Job<Page<Book>>() {
            @Override
            public Page<Book> run() throws Exception {
                Api api = Api.get();
                if (MODE_DOWNLOADED.equals(jobMode)) {
                    return api.downloaded(requestPage);
                }
                return api.saved(requestPage);
            }
        }, new Task.Ok<Page<Book>>() {
            @Override
            public void ok(Page<Book> result) {
                if (token != requestToken) {
                    return;
                }
                loading = false;
                Activity activity = activity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (result == null) {
                    showPageFailure(getContext().getString(R.string.mybooks_error_title));
                    return;
                }
                page = result.current > 0 ? result.current : requestPage;
                hasMore = result.hasMore();
                loadFailed = false;
                loadMoreFailed = false;
                List<Book> items = result.items;
                if (items != null && !items.isEmpty()) {
                    adapter.addItems(items);
                }
                syncState();
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != requestToken) {
                    return;
                }
                loading = false;
                Activity activity = activity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (Ui.handleAuthError(activity, e)) {
                    return;
                }
                showPageFailure(Ui.errorText(e));
            }
        });
    }

    private void showPageFailure(String message) {
        // page 仍是最后成功页；只有成功响应的 hasMore() 才能判定末页。
        hasMore = true;
        loadFailed = adapter.isEmpty();
        loadMoreFailed = !loadFailed;
        if (loadFailed) {
            if (errorDesc != null) {
                errorDesc.setText(message);
            }
        } else {
            Ui.toast(getContext(), message);
        }
        syncState();
    }

    private void syncState() {
        Activity activity = activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        boolean empty = adapter.isEmpty();
        boolean firstLoading = loading && empty && !loadFailed;
        Ui.display(skeletonView, firstLoading);
        Ui.display(emptyView, loadedOnce && !loading && !loadFailed && empty);
        Ui.display(errorView, loadedOnce && !loading && loadFailed && empty);
        Ui.display(loadingMore, loading && !empty && hasMore);
        // 即使请求期间最后一项被移除，也保留显式续页重试入口。
        Ui.display(loadMoreRetry, !loading && loadMoreFailed);
        if (loadMoreRetry != null) {
            loadMoreRetry.setEnabled(!loading && loadMoreFailed);
        }
        updateEmptyTexts();
    }

    private void updateEmptyTexts() {
        boolean downloaded = MODE_DOWNLOADED.equals(mode);
        if (emptyTitle != null) {
            emptyTitle.setText(downloaded
                    ? R.string.mybooks_empty_downloaded_title : R.string.mybooks_empty_saved_title);
        }
        if (emptyDesc != null) {
            emptyDesc.setText(downloaded
                    ? R.string.mybooks_empty_downloaded_desc : R.string.mybooks_empty_saved_desc);
        }
    }

    private void showItemMenu(final Book book) {
        final Activity activity = activity();
        if (book == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final boolean downloadedMode = MODE_DOWNLOADED.equals(mode);
        CharSequence[] items = new CharSequence[]{
                activity.getString(downloadedMode
                        ? R.string.item_delete_download : R.string.item_unsave)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.item_more)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmRemove(book, downloadedMode);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmRemove(final Book book, final boolean downloadedMode) {
        final Activity activity = activity();
        if (book == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        int titleRes = downloadedMode
                ? R.string.confirm_delete_title : R.string.confirm_unsave_title;
        int messageRes = downloadedMode
                ? R.string.confirm_delete_message : R.string.confirm_unsave_message;
        int actionRes = downloadedMode
                ? R.string.item_delete_download : R.string.item_unsave;
        String message = activity.getString(messageRes, safeBookTitle(book));
        Ui.confirm(activity, activity.getString(titleRes), message,
                activity.getString(actionRes), new Runnable() {
                    @Override
                    public void run() {
                        removeBook(book, downloadedMode);
                    }
                });
    }

    private void removeBook(final Book book, final boolean downloadedMode) {
        if (book == null) {
            return;
        }
        Task.run(new Task.Job<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                if (downloadedMode) {
                    return Boolean.valueOf(Api.get().deleteDownloaded(book.id));
                }
                return Boolean.valueOf(Api.get().unsave(book.id));
            }
        }, new Task.Ok<Boolean>() {
            @Override
            public void ok(Boolean value) {
                Activity activity = activity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                adapter.remove(book);
                loadFailed = false;
                syncState();
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                Activity activity = activity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (Ui.handleAuthError(activity, e)) {
                    return;
                }
                Ui.toast(getContext(), Ui.errorText(e));
            }
        });
    }

    private Activity activity() {
        Context context = getContext();
        return context instanceof Activity ? (Activity) context : null;
    }

    private static String safeBookTitle(Book book) {
        if (book == null) {
            return "";
        }
        return Ui.text(book.title, Ui.text(book.hash, ""));
    }

    /** 列表行适配器：封面 56×76 + 书名 / 作者 / 状态 chip + 更多按钮。 */
    private static final class BookListAdapter extends BaseAdapter {

        interface OnMoreClickListener {
            void onMore(Book book);
        }

        private final LayoutInflater inflater;
        private final OnMoreClickListener listener;
        private final List<Book> items = new ArrayList<Book>();
        private String mode = MODE_SAVED;

        BookListAdapter(Context context, OnMoreClickListener listener) {
            this.inflater = LayoutInflater.from(context);
            this.listener = listener;
        }

        void setMode(String mode) {
            this.mode = mode;
            notifyDataSetChanged();
        }

        void addItems(List<Book> books) {
            if (books == null || books.isEmpty()) {
                return;
            }
            items.addAll(books);
            notifyDataSetChanged();
        }

        void clear() {
            if (items.isEmpty()) {
                return;
            }
            items.clear();
            notifyDataSetChanged();
        }

        @Override
        public boolean isEmpty() {
            return items.isEmpty();
        }

        void remove(Book book) {
            if (book == null) {
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                Book item = items.get(i);
                if (item == book || (item != null && item.id == book.id)) {
                    items.remove(i);
                    notifyDataSetChanged();
                    return;
                }
            }
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Book getItem(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            Book book = getItem(position);
            return book == null ? position : book.id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            ViewHolder holder;
            if (view == null) {
                view = inflater.inflate(R.layout.row_book_list, parent, false);
                holder = new ViewHolder();
                holder.cover = (ImageView) view.findViewById(R.id.iv_row_cover);
                holder.title = (TextView) view.findViewById(R.id.tv_row_title);
                holder.author = (TextView) view.findViewById(R.id.tv_row_author);
                holder.status = (TextView) view.findViewById(R.id.tv_row_status);
                holder.more = view.findViewById(R.id.btn_row_more);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            final Book book = getItem(position);
            if (book == null) {
                if (holder.title != null) {
                    holder.title.setText("");
                }
                if (holder.author != null) {
                    holder.author.setText("");
                }
                if (holder.status != null) {
                    holder.status.setText("");
                }
                if (holder.cover != null) {
                    holder.cover.setImageDrawable(null);
                }
                if (holder.more != null) {
                    holder.more.setOnClickListener(null);
                }
                return view;
            }

            if (holder.title != null) {
                holder.title.setText(Ui.text(book.title, ""));
            }
            if (holder.author != null) {
                holder.author.setText(Ui.text(book.author, ""));
            }
            if (holder.status != null) {
                holder.status.setText(MODE_DOWNLOADED.equals(mode)
                        ? R.string.status_downloaded : R.string.status_saved);
            }
            if (holder.cover != null) {
                ImageLoader.get().load(book.cover, holder.cover);
            }
            if (holder.more != null) {
                holder.more.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) {
                            listener.onMore(book);
                        }
                    }
                });
            }
            return view;
        }

        private static final class ViewHolder {
            ImageView cover;
            TextView title;
            TextView author;
            TextView status;
            View more;
        }
    }
}
