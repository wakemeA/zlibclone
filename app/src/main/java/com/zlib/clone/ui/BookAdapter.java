package com.zlib.clone.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.zlib.clone.R;
import com.zlib.clone.model.Book;

import java.util.ArrayList;
import java.util.List;

/**
 * 书库网格适配器（两列/宽屏三列）：
 * 封面按 3:4 居中裁剪，高度由实际列宽动态计算；书名最多 2 行、作者 1 行。
 */
public class BookAdapter extends BaseAdapter {

    /** 封面宽高比：宽 3、高 4。 */
    private static final int COVER_WIDTH_RATIO = 3;
    private static final int COVER_HEIGHT_RATIO = 4;
    /** 取不到列数时的兜底列数。 */
    private static final int DEFAULT_COLUMNS = 2;

    private final LayoutInflater inflater;
    private final List<Book> items = new ArrayList<Book>();

    /** 上一次计算封面尺寸时的父容器宽度与列数，用于避免每格重复计算。 */
    private int lastParentWidth = -1;
    private int lastColumns = -1;
    private int coverHeight = -1;

    public BookAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    // ------------------------------------------------------------------
    // 数据操作
    // ------------------------------------------------------------------

    /** 整体替换数据（切榜/搜索/清空恢复时用）。 */
    public void setItems(List<Book> books) {
        items.clear();
        if (books != null) {
            items.addAll(books);
        }
        notifyDataSetChanged();
    }

    /** 追加一页数据（分页加载）。 */
    public void addItems(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return;
        }
        items.addAll(books);
        notifyDataSetChanged();
    }

    public void clear() {
        if (items.isEmpty()) {
            return;
        }
        items.clear();
        notifyDataSetChanged();
    }

    /** 返回当前数据的浅拷贝，便于页面保存/恢复快照。 */
    public List<Book> getItems() {
        return new ArrayList<Book>(items);
    }

    /** 按对象引用移除一条（保留给列表类页面复用）。 */
    public void remove(Book book) {
        if (book != null && items.remove(book)) {
            notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------
    // BaseAdapter
    // ------------------------------------------------------------------

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
        if (view == null) {
            view = inflater.inflate(R.layout.row_book_card, parent, false);
            ViewHolder holder = new ViewHolder();
            holder.cover = (ImageView) view.findViewById(R.id.iv_card_cover);
            holder.title = (TextView) view.findViewById(R.id.tv_card_title);
            holder.author = (TextView) view.findViewById(R.id.tv_card_author);
            view.setTag(holder);
        }

        Object tag = view.getTag();
        if (!(tag instanceof ViewHolder)) {
            return view;
        }
        ViewHolder holder = (ViewHolder) tag;
        Book book = getItem(position);

        updateCoverSize(parent);
        if (holder.cover != null) {
            if (coverHeight > 0) {
                ViewGroup.LayoutParams lp = holder.cover.getLayoutParams();
                if (lp != null && lp.height != coverHeight) {
                    lp.height = coverHeight;
                    holder.cover.setLayoutParams(lp);
                }
            }
            String coverUrl = (book == null || book.cover == null) ? "" : book.cover.trim();
            if (coverUrl.length() == 0) {
                // 无封面：清掉旧图并断开 ImageLoader 的 tag 校验，避免复用串图。
                holder.cover.setTag(null);
                holder.cover.setImageDrawable(null);
            } else {
                ImageLoader.get().load(book.cover, holder.cover);
            }
        }

        if (holder.title != null) {
            holder.title.setText(book == null || book.title == null ? "" : book.title);
        }
        if (holder.author != null) {
            holder.author.setText(book == null || book.author == null ? "" : book.author);
        }
        return view;
    }

    /**
     * 按 GridView 的实际列宽算封面高度（3:4）。
     * 首帧还没量出宽度时退化为屏幕宽度估算；量出后会自动重算。
     */
    private void updateCoverSize(ViewGroup parent) {
        int columns = DEFAULT_COLUMNS;
        if (parent instanceof GridView) {
            int numColumns = ((GridView) parent).getNumColumns();
            if (numColumns > 0) {
                columns = numColumns;
            }
        }
        int parentWidth = (parent == null) ? 0 : parent.getWidth();
        if (parentWidth == lastParentWidth && columns == lastColumns && coverHeight > 0) {
            return;
        }

        Resources resources = inflater.getContext().getResources();
        int columnWidth;
        if (parentWidth > 0) {
            int spacing = 0;
            if (parent instanceof GridView) {
                spacing = Math.round(((GridView) parent).getHorizontalSpacing());
            }
            int available = parentWidth - parent.getPaddingLeft() - parent.getPaddingRight()
                    - spacing * (columns - 1);
            columnWidth = available / columns;
        } else {
            int margin = resources.getDimensionPixelSize(R.dimen.screen_margin);
            int gap = resources.getDimensionPixelSize(R.dimen.space_12);
            DisplayMetrics metrics = resources.getDisplayMetrics();
            int available = metrics.widthPixels - margin * 2 - gap * (columns - 1);
            columnWidth = available / columns;
        }

        // 卡片左右各 8dp 内边距，封面宽度 = 列宽 - 16dp。
        int cardPadding = resources.getDimensionPixelSize(R.dimen.space_8);
        int width = columnWidth - cardPadding * 2;
        if (width <= 0) {
            width = Math.max(1, columnWidth);
        }
        int height = width * COVER_HEIGHT_RATIO / COVER_WIDTH_RATIO;
        if (height <= 0) {
            height = width;
        }

        coverHeight = height;
        lastParentWidth = parentWidth;
        lastColumns = columns;
    }

    /** 复用缓存，避免每格 findViewById。 */
    private static final class ViewHolder {
        ImageView cover;
        TextView title;
        TextView author;
    }
}
