package com.zlib.clone.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.zlib.clone.data.Task;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.DownloadInfo;
import com.zlib.clone.model.Format;
import com.zlib.clone.net.Api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.zlib.clone.R;

/**
 * 书籍详情：展示字段与封面，收藏/取消、下载（格式弹窗）、在线阅读。
 */
public class BookDetailActivity extends Activity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_HASH = "hash";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUTHOR = "author";
    public static final String EXTRA_COVER = "cover";

    private static final int REQUEST_STORAGE = 1001;

    private int bookId;
    private String bookHash;
    private Book book;
    private List<Format> formats;

    private ImageView coverView;
    private TextView titleView;
    private TextView authorView;
    private TextView metaView;
    private TextView descLabelView;
    private TextView descView;
    private TextView saveButton;
    private TextView downloadButton;
    private TextView readButton;
    private ProgressBar progressBar;
    private View detailError;
    private TextView detailErrorText;
    private View detailRetry;
    private TextView readReason;
    private boolean detailLoading;
    private int detailRequestToken;
    /** 从格式请求到弹窗选择、下载地址请求全程防止重复点击。 */
    private boolean downloadBusy;
    private AlertDialog downloadDialog;

    /** 从列表打开详情页的便捷方法。 */
    public static void open(Context context, Book book) {
        if (context == null || book == null) {
            return;
        }
        Intent intent = new Intent(context, BookDetailActivity.class);
        intent.putExtra(EXTRA_ID, book.id);
        intent.putExtra(EXTRA_HASH, book.hash);
        intent.putExtra(EXTRA_TITLE, book.title);
        intent.putExtra(EXTRA_AUTHOR, book.author);
        intent.putExtra(EXTRA_COVER, book.cover);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // 忽略启动失败（理论上不会发生）。
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_book_detail);

        coverView = (ImageView) findViewById(R.id.iv_detail_cover);
        titleView = (TextView) findViewById(R.id.tv_detail_title);
        authorView = (TextView) findViewById(R.id.tv_detail_author);
        metaView = (TextView) findViewById(R.id.tv_detail_meta);
        descLabelView = (TextView) findViewById(R.id.tv_detail_desc_label);
        descView = (TextView) findViewById(R.id.tv_detail_desc);
        saveButton = (TextView) findViewById(R.id.btn_detail_save);
        downloadButton = (TextView) findViewById(R.id.btn_detail_download);
        readButton = (TextView) findViewById(R.id.btn_detail_read);
        progressBar = (ProgressBar) findViewById(R.id.pb_detail);
        detailError = findViewById(R.id.ll_detail_error);
        detailErrorText = (TextView) findViewById(R.id.tv_detail_error);
        detailRetry = findViewById(R.id.btn_detail_retry);
        readReason = (TextView) findViewById(R.id.tv_detail_read_reason);
        detailRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadBook();
            }
        });

        View backButton = findViewById(R.id.btn_detail_back);
        if (backButton != null) {
            backButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        Ui.display(descLabelView, false);
        Ui.display(descView, false);
        saveButton.setText(R.string.save_book);

        Intent intent = getIntent();
        bookId = intent == null ? 0 : intent.getIntExtra(EXTRA_ID, 0);
        bookHash = intent == null ? null : intent.getStringExtra(EXTRA_HASH);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        String author = intent == null ? null : intent.getStringExtra(EXTRA_AUTHOR);
        String cover = intent == null ? null : intent.getStringExtra(EXTRA_COVER);

        if (title != null && title.length() > 0) {
            titleView.setText(title);
        }
        if (author != null) {
            authorView.setText(author);
        }
        if (cover != null && cover.length() > 0) {
            ImageLoader.get().load(cover, coverView);
        }

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSaved();
            }
        });
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDownloadClicked();
            }
        });
        readButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readOnline();
            }
        });

        if (bookId <= 0) {
            Ui.toast(this, "书籍信息无效");
            finish();
            return;
        }
        loadBook();
    }

    private void loadBook() {
        if (detailLoading || isFinishing() || isDestroyed()) {
            return;
        }
        final int token = ++detailRequestToken;
        detailLoading = true;
        Ui.display(detailError, false);
        detailRetry.setEnabled(false);
        updateDetailActions();
        Ui.display(progressBar, true);
        Task.run(new Task.Job<Book>() {
            @Override
            public Book run() throws Exception {
                return Api.get().book(bookId, bookHash);
            }
        }, new Task.Ok<Book>() {
            @Override
            public void ok(Book result) {
                if (token != detailRequestToken || isFinishing() || isDestroyed()) {
                    return;
                }
                detailLoading = false;
                Ui.display(progressBar, false);
                if (result == null) {
                    showDetailError(getString(R.string.detail_load_failed));
                    return;
                }
                Ui.display(detailError, false);
                bind(result);
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (token != detailRequestToken || isFinishing() || isDestroyed()) {
                    return;
                }
                detailLoading = false;
                Ui.display(progressBar, false);
                if (Ui.handleAuthError(BookDetailActivity.this, e)) {
                    return;
                }
                showDetailError(Ui.errorText(e));
            }
        });
    }

    private void showDetailError(String message) {
        detailErrorText.setText(message);
        detailRetry.setEnabled(true);
        Ui.display(detailError, true);
        updateDetailActions();
    }

    private void updateDetailActions() {
        boolean ready = book != null && !detailLoading;
        saveButton.setEnabled(ready);
        downloadButton.setEnabled(ready && !downloadBusy);
        boolean readable = ready && book.readOnlineAvailable
                && book.readOnlineUrl != null && book.readOnlineUrl.trim().length() > 0;
        readButton.setEnabled(readable);
        // 本批仅局部修复不可读的视觉提示；全局 disabled selector 留到 Batch 3。
        readButton.setBackgroundResource(readable
                ? R.drawable.bg_primary_button : R.drawable.bg_skeleton);
        readButton.setTextColor(getResources().getColor(readable
                ? R.color.on_primary : R.color.ink_secondary));
        Ui.display(readReason, ready && !readable);
        if (ready && !readable) {
            readReason.setText(book.readOnlineAvailable
                    ? R.string.read_online_missing : R.string.read_online_unavailable);
        }
    }

    private void setDownloadBusy(boolean busy) {
        downloadBusy = busy;
        downloadButton.setEnabled(book != null && !detailLoading && !busy);
    }

    private void bind(Book value) {
        book = value;
        titleView.setText(safe(value.title));
        authorView.setText(safe(value.author));

        StringBuilder meta = new StringBuilder();
        appendMeta(meta, "格式", value.extension);
        appendMeta(meta, "大小", value.filesizeString);
        appendMeta(meta, "年份", value.year);
        appendMeta(meta, "语言", value.language);
        metaView.setText(meta.toString());

        if (value.description != null && value.description.trim().length() > 0) {
            descView.setText(value.description);
            Ui.display(descLabelView, true);
            Ui.display(descView, true);
        }
        ImageLoader.get().load(value.cover, coverView);
        updateSaveButton();
        updateDetailActions();
    }

    private static void appendMeta(StringBuilder sb, String label, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("    ");
        }
        sb.append(label).append("：").append(value);
    }

    private void updateSaveButton() {
        boolean saved = book != null && book.isUserSavedBook;
        saveButton.setText(saved ? R.string.unsave_book : R.string.save_book);
    }

    private void toggleSaved() {
        if (book == null) {
            Ui.toast(this, "书籍信息加载中，请稍候");
            return;
        }
        final boolean wasSaved = book.isUserSavedBook;
        saveButton.setEnabled(false);
        Task.run(new Task.Job<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                if (wasSaved) {
                    return Boolean.valueOf(Api.get().unsave(book.id));
                }
                return Boolean.valueOf(Api.get().save(book.id));
            }
        }, new Task.Ok<Boolean>() {
            @Override
            public void ok(Boolean result) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                saveButton.setEnabled(true);
                if (book != null) {
                    book.isUserSavedBook = !wasSaved;
                }
                updateSaveButton();
                Ui.toast(BookDetailActivity.this, wasSaved ? "已取消收藏" : "已收藏");
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                saveButton.setEnabled(true);
                if (Ui.handleAuthError(BookDetailActivity.this, e)) {
                    return;
                }
                Ui.toast(BookDetailActivity.this, Ui.errorText(e));
            }
        });
    }

    private void onDownloadClicked() {
        if (downloadBusy || detailLoading) {
            return;
        }
        if (book == null) {
            Ui.toast(this, "书籍信息加载中，请稍候");
            return;
        }
        if (!ensureStoragePermission()) {
            return;
        }
        requestFormats();
    }

    private void requestFormats() {
        setDownloadBusy(true);
        Ui.display(progressBar, true);
        Task.run(new Task.Job<List<Format>>() {
            @Override
            public List<Format> run() throws Exception {
                return Api.get().formats(book.id, book.hash);
            }
        }, new Task.Ok<List<Format>>() {
            @Override
            public void ok(List<Format> result) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Ui.display(progressBar, false);
                formats = result;
                if (result == null || result.isEmpty()) {
                    confirmDefaultDownload();
                } else {
                    showFormatDialog();
                }
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Ui.display(progressBar, false);
                if (Ui.handleAuthError(BookDetailActivity.this, e)) {
                    setDownloadBusy(false);
                    return;
                }
                confirmDefaultDownload();
            }
        });
    }

    private void confirmDefaultDownload() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 未获明确确认前，不请求默认文件地址，也不加入系统下载队列。
        downloadDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.download_default_title)
                .setMessage(R.string.download_default_message)
                .setPositiveButton(R.string.download_default_action,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startDownload(null);
                            }
                        })
                .setNegativeButton(R.string.action_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setDownloadBusy(false);
                    }
                })
                .create();
        downloadDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                setDownloadBusy(false);
            }
        });
        downloadDialog.show();
    }

    private void showFormatDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_formats, null);
        ListView listView = (ListView) content.findViewById(R.id.lv_formats);
        final List<String> labels = new ArrayList<String>();
        for (int i = 0; formats != null && i < formats.size(); i++) {
            Format format = formats.get(i);
            String label = format == null ? getString(R.string.download_format_unknown)
                    : Ui.text(format.extension, getString(R.string.download_format_unknown)).toUpperCase(Locale.US);
            if (format != null && format.filesizeString != null && format.filesizeString.length() > 0) {
                label = label + "    " + format.filesizeString;
            }
            labels.add(label);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setDownloadBusy(false);
                    }
                })
                .create();
        downloadDialog = dialog;
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                setDownloadBusy(false);
            }
        });
        if (listView != null) {
            listView.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    dialog.dismiss();
                    Format chosen = (formats != null && position >= 0 && position < formats.size())
                            ? formats.get(position) : null;
                    if (chosen == null || chosen.id <= 0
                            || chosen.hash == null || chosen.hash.trim().length() == 0) {
                        confirmDefaultDownload();
                    } else {
                        startDownload(chosen);
                    }
                }
            });
        }
        dialog.show();
    }

    private void startDownload(final Format chosen) {
        if (book == null || isFinishing() || isDestroyed()) {
            setDownloadBusy(false);
            return;
        }
        setDownloadBusy(true);
        Ui.toast(this, "正在获取下载地址…");
        Task.run(new Task.Job<DownloadInfo>() {
            @Override
            public DownloadInfo run() throws Exception {
                // 不再吞掉指定格式异常后静默下载另一文件；鉴权异常也必须传至 UI。
                return Api.get().downloadInfo(chosen == null ? book.id : chosen.id,
                        chosen == null ? book.hash : chosen.hash);
            }
        }, new Task.Ok<DownloadInfo>() {
            @Override
            public void ok(DownloadInfo info) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setDownloadBusy(false);
                if (info == null || info.url == null || info.url.length() == 0) {
                    Ui.toast(BookDetailActivity.this, "获取下载地址失败");
                    return;
                }
                if (!info.allowDownload) {
                    Ui.toast(BookDetailActivity.this, "无法下载这本书（可能已到达下载额度限制）");
                    return;
                }
                enqueueDownload(info);
            }
        }, new Task.Err() {
            @Override
            public void err(Exception e) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setDownloadBusy(false);
                if (Ui.handleAuthError(BookDetailActivity.this, e)) {
                    return;
                }
                Ui.toast(BookDetailActivity.this, Ui.errorText(e));
            }
        });
    }

    private void enqueueDownload(DownloadInfo info) {
        try {
            String url = info.url;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = Api.get().absolute(url);
            }
            String name = sanitizeFilename(info.description);
            if (name.length() == 0) {
                name = sanitizeFilename(book.title);
            }
            if (name.length() == 0) {
                name = "book_" + book.id;
            }
            String extension = info.extension;
            if (extension == null || extension.length() == 0) {
                extension = book.extension;
            }
            if (extension != null && extension.length() > 0
                    && !name.toLowerCase(Locale.US).endsWith("." + extension.toLowerCase(Locale.US))) {
                name = name + "." + extension;
            }

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription(book.title);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try {
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(true);
            } catch (Throwable ignored) {
                // 老系统不支持时忽略。
            }
            Map<String, String> headers = Api.get().headers();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    try {
                        request.addRequestHeader(entry.getKey(), entry.getValue());
                    } catch (Throwable ignored) {
                        // DownloadManager 对非 ASCII header 值会抛异常，跳过该条继续下载。
                    }
                }
            }
            manager.enqueue(request);
            Ui.toast(this, "已开始下载：" + name);
        } catch (Throwable t) {
            Ui.toast(this, "下载失败：" + safe(t.getMessage()));
        }
    }

    private void readOnline() {
        if (book == null) {
            Ui.toast(this, "书籍信息加载中，请稍候");
            return;
        }
        if (!book.readOnlineAvailable) {
            Ui.toast(this, getString(R.string.read_online_unavailable));
            return;
        }
        if (book.readOnlineUrl == null || book.readOnlineUrl.trim().length() == 0) {
            Ui.toast(this, getString(R.string.read_online_missing));
            return;
        }
        String target = book.readOnlineUrl.trim();
        try {
            String resolved = Api.get().absolute(target);
            if (resolved != null && resolved.length() > 0) {
                target = resolved;
            }
        } catch (Throwable ignored) {
            // 保持原值。
        }
        ReaderView.saveLastUrl(this, target);
        ReaderView.saveLastTitle(this, book.title);
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderView.KEY_URL, target);
        intent.putExtra(ReaderView.KEY_TITLE, book.title);
        startActivity(intent);
    }

    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29) {
            return true;
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            onDownloadClicked();
        } else {
            Ui.toast(this, "需要存储权限才能下载文件");
        }
    }

    @Override
    protected void onDestroy() {
        detailRequestToken++;
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
        super.onDestroy();
    }

    private static String sanitizeFilename(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
