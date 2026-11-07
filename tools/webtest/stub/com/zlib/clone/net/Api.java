package com.zlib.clone.net;

import com.zlib.clone.model.Book;
import com.zlib.clone.model.Page;

/** JVM 测试用替身：真实 Api 依赖 Android 框架，本测试只验证网页分类数据源与映射纯逻辑。 */
public final class Api {
    private static final Api INSTANCE = new Api();

    public static Api get() {
        return INSTANCE;
    }

    public String base() {
        return "";
    }

    public Page<Book> search(String query, int page) {
        throw new UnsupportedOperationException("JVM test stub");
    }

    public Book book(int id, String hash) {
        throw new UnsupportedOperationException("JVM test stub");
    }
}
