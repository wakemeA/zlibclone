package com.zlib.clone.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用分页结果。
 *
 * 服务端分页对象形如 {limit,current,before,next,total_items,total_pages}，
 * 其中 before/next 可能是布尔 false 或 null（解析侧不使用这两个字段）；
 * 分页对象缺失时按 current=1 / totalPages=1 处理，即"只有一页"。
 */
public class Page<T> {

    public List<T> items;

    public int current;
    public int totalPages;
    public int totalItems;

    public Page() {
        this.items = new ArrayList<T>();
        this.current = 1;
        this.totalPages = 1;
        this.totalItems = 0;
    }

    public Page(List<T> items, int current, int totalPages, int totalItems) {
        this.items = (items == null) ? new ArrayList<T>() : items;
        this.current = (current < 1) ? 1 : current;
        this.totalPages = (totalPages < 1) ? 1 : totalPages;
        this.totalItems = (totalItems < 0) ? 0 : totalItems;
    }

    /** 还有下一页：current < totalPages。 */
    public boolean hasMore() {
        return current < totalPages;
    }
}
