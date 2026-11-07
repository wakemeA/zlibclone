package com.zlib.clone.net;

import com.zlib.clone.model.Category;
import com.zlib.clone.model.CategoryPage;

public final class DebugFetch {
    public static void main(String[] args) throws Exception {
        WebCategorySource.Tree tree = WebCategorySource.fetchTree("https://z-lib.gl/");
        Category history = null;
        for (Category c : tree.roots) {
            if (c.sourceCategoryId == 17) history = c;
        }
        CategoryPage plain = WebCategorySource.fetchBooks(tree.baseUrl, history, "", 1, "");
        System.out.println("plain items=" + plain.items.size() + " searchBase=" + plain.searchBase
                + " pattern=" + plain.pageUrlPattern);
        System.out.println("history.searchBase=" + history.searchBase);
        System.out.println("url(zh p1)=" + WebCategorySource.buildBooksUrl(tree.baseUrl, history, "chinese", 1));
        CategoryPage zh = WebCategorySource.fetchBooks(tree.baseUrl, history, "chinese", 1, "");
        System.out.println("zh items=" + zh.items.size() + " current=" + zh.current + " total=" + zh.totalPages
                + " searchBase=" + zh.searchBase);
        System.out.println("zh pattern=[" + zh.pageUrlPattern + "]");
        System.out.println("url(zh p2)=" + WebCategorySource.buildBooksUrl(tree.baseUrl, history, "chinese", 2));
    }
}
