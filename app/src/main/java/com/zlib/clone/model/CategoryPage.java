package com.zlib.clone.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类书单的一页（网页解析结果）。
 *
 * 注意：这里的分页完全来自网页自身的 pagerOptions，与 /eapi 搜索的分页
 * （每页 20 条、total_items 上限 500）无关，不能混用。
 */
public class CategoryPage {

    public List<Book> items = new ArrayList<Book>();

    /** 当前页（1 开始，取网页 pagerOptions.pageCurrent，缺失时用请求页）。 */
    public int current = 1;
    /** 网页 pagerOptions.pagesTotal，缺失时为当前页（即没有下一页）。 */
    public int totalPages = 1;
    /** 网页明确告知「没有找到」时为 true（区别于解析失败）。 */
    public boolean empty = false;
    /** 网页给出的分页 URL 模板（含 %number%），下一页优先用它。 */
    public String pageUrlPattern = "";
    /** 页面里 searchForm 的 action（如 /category/17/History/s/），用于拼语言筛选 URL。 */
    public String searchBase = "";

    /** 是否还有下一页。 */
    public boolean hasMore() {
        return current < totalPages;
    }
}
