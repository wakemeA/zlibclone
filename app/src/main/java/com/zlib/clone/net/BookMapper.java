package com.zlib.clone.net;

import com.zlib.clone.model.Book;
import com.zlib.clone.model.Page;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页书目 → 现有原生详情接口的映射（唯一可靠的映射路径）。
 *
 * 背景与实测（2026-11）：
 * - 网页书籍卡片带数字 ID、ISBN（部分书）、termshash、href（/book/{短token}/…）；
 * - /eapi 的 {hash} **不是**网页 href 的短 token，也不是 termshash（实测均返回 Invalid hash）；
 * - 但 /eapi 的图书 ID 与网页数字 ID 相同。可靠做法：
 *   1) 用网页卡片上的 ISBN 调 /eapi/book/search（无 ISBN 时退回用完整标题检索）；
 *   2) 在搜索结果里只接受 **ID 完全相等** 的条目（不按标题相似度关联版本）；
 *   3) 用该条目的 id+hash 拉 /eapi/book/{id}/{hash}；
 *   4) 校验详情 terms_hash 与网页卡片 termshash 相等（缺失时退回 href 短 token 相等）。
 *   任一步不满足即视为「无法可靠映射」，由界面回退到网页详情，不强行关联。
 */
public final class BookMapper {

    private static final Pattern HREF_TOKEN = Pattern.compile("/book/([^/]+)/");

    /** 映射结果：book 非空表示可进入原生详情；为空时 reason 记录内部原因（仅日志/说明用）。 */
    public static final class Result {
        public final Book book;
        public final String reason;

        private Result(Book book, String reason) {
            this.book = book;
            this.reason = reason;
        }
    }

    private BookMapper() {
    }

    /**
     * 详情映射缓存键：优先用书籍 ID（网页书目与 /eapi 的书籍 ID 相同），
     * ID 缺失时退回网页详情绝对地址；都不可用时返回空串（不缓存）。
     *
     * 缓存本体由界面层（CategoryView 的 LruCache）持有，这里只负责键的语义，
     * 使 BookMapper 保持纯 Java（可被 JVM 联网测试直接编译/运行，不依赖 android.*）。
     */
    public static String cacheKey(Book webBook) {
        if (webBook == null) {
            return "";
        }
        if (webBook.id > 0) {
            return "id:" + webBook.id;
        }
        String url = webBook.webUrl == null ? "" : webBook.webUrl.trim();
        return url.length() == 0 ? "" : "url:" + url;
    }

    /**
     * 尝试把网页分类页的一本书映射到原生详情接口。
     * 该方法是阻塞的，调用方应放在后台线程（Task.run）。
     */
    public static Result mapToNative(Book webBook) {
        if (webBook == null || webBook.id <= 0) {
            return fail("网页书目缺少有效 ID");
        }
        if (webBook.termsHash == null || webBook.termsHash.length() == 0) {
            return fail("网页书目缺少 termshash，无法校验映射");
        }
        boolean byIsbn = isIsbn(webBook.isbn);
        String query = byIsbn ? normalizeIsbn(webBook.isbn) : webBook.title;
        if (query == null || query.trim().length() == 0) {
            return fail("网页书目既无 ISBN 也无标题");
        }
        Book candidate;
        try {
            Page<Book> page = Api.get().search(query.trim(), 1);
            candidate = findSameId(page, webBook.id);
        } catch (Exception e) {
            return fail("检索失败（" + e.getClass().getSimpleName() + "）");
        }
        if (candidate == null) {
            return fail(byIsbn ? "按 ISBN 检索未命中同一 ID" : "按标题检索未命中同一 ID");
        }
        if (candidate.hash == null || candidate.hash.length() == 0) {
            return fail("检索结果缺少 hash");
        }
        Book detail;
        try {
            detail = Api.get().book(webBook.id, candidate.hash);
        } catch (Exception e) {
            return fail("详情请求失败（" + e.getClass().getSimpleName() + "）");
        }
        if (detail == null || detail.id != webBook.id) {
            return fail("详情 ID 与网页书目不一致");
        }
        if (!identityMatches(webBook, detail)) {
            return fail("详情标识校验不一致");
        }
        // 回填网页列表里更好看的封面（详情接口封面可能为空）。
        if ((detail.cover == null || detail.cover.length() == 0) && webBook.cover != null) {
            detail.cover = webBook.cover;
        }
        return new Result(detail, "");
    }

    /** 在搜索结果里只按 ID 完全相等匹配（绝不按标题相似度配对）。 */
    private static Book findSameId(Page<Book> page, int id) {
        if (page == null || page.items == null) {
            return null;
        }
        for (Book item : page.items) {
            if (item != null && item.id == id) {
                return item;
            }
        }
        return null;
    }

    /**
     * 身份校验：优先比较详情 terms_hash 与网页卡片 termshash；
     * 缺失时退回 href 中的短 token；两者都无法校验则视为不可靠。
     */
    static boolean identityMatches(Book web, Book detail) {
        String webTerms = web.termsHash == null ? "" : web.termsHash.trim();
        String detailTerms = detail.termsHash == null ? "" : detail.termsHash.trim();
        if (webTerms.length() > 0 && detailTerms.length() > 0) {
            return webTerms.equalsIgnoreCase(detailTerms);
        }
        String webToken = hrefToken(web.href);
        String detailToken = hrefToken(detail.href);
        return webToken.length() > 0 && webToken.equals(detailToken);
    }

    static String hrefToken(String href) {
        if (href == null) {
            return "";
        }
        Matcher m = HREF_TOKEN.matcher(href);
        return m.find() ? m.group(1) : "";
    }

    /** ISBN 校验：去掉连字符后 10 或 13 位（允许 10 位末位 X）。 */
    static boolean isIsbn(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.replace("-", "").replace(" ", "").trim();
        if (value.length() == 10) {
            for (int i = 0; i < 9; i++) {
                if (!Character.isDigit(value.charAt(i))) {
                    return false;
                }
            }
            char last = value.charAt(9);
            return Character.isDigit(last) || last == 'X' || last == 'x';
        }
        if (value.length() == 13) {
            for (int i = 0; i < 13; i++) {
                if (!Character.isDigit(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static String normalizeIsbn(String raw) {
        return raw == null ? "" : raw.replace("-", "").replace(" ", "").trim()
                .toUpperCase(Locale.US);
    }

    private static Result fail(String reason) {
        return new Result(null, reason);
    }
}
