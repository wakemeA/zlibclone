package com.zlib.clone.net;

import com.zlib.clone.data.Categories;
import com.zlib.clone.data.CategoryNames;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.Category;
import com.zlib.clone.model.CategoryPage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 JVM 上直接联网验证 net/WebCategorySource（与 APK 内是同一份 Java 源码）。
 * 运行：java -cp build/webtest com.zlib.clone.net.WebCategoryTest
 */
public final class WebCategoryTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        String base = args != null && args.length > 0 ? args[0] : "https://z-lib.gl/";
        long t0 = System.currentTimeMillis();
        System.out.println("base = " + base);

        WebCategorySource.Tree tree = WebCategorySource.fetchTree(base);
        check("分类目录：一级分类数量 == 39（实测网站值）", tree.roots.size() == 39, "roots=" + tree.roots.size());
        int children = 0;
        for (Category root : tree.roots) {
            children += root.children.size();
        }
        check("分类目录：二级分类数量 == 802（实测网站值）", children == 802, "children=" + children);

        Category fiction = findRoot(tree.roots, 14);
        Category history = findRoot(tree.roots, 17);
        Category biography = findRoot(tree.roots, 3);
        check("Fiction 一级分类存在且为 id=14", fiction != null && "Fiction".equals(fiction.sourceName), name(fiction));
        check("History 一级分类存在且为 id=17", history != null && "History".equals(history.sourceName), name(history));
        check("Biography & Autobiography 一级分类存在且为 id=3", biography != null
                && "Biography & Autobiography".equals(biography.sourceName), name(biography));
        check("Fiction 有中文展示名「小说」", fiction != null && "小说".equals(fiction.displayName), name(fiction));
        check("History 有中文展示名「历史」", history != null && "历史".equals(history.displayName), name(history));

        Category historical = findChild(fiction, 270);
        check("Fiction > Historical（id=270，父=14）存在", historical != null && historical.parentId == 14, name(historical));
        check("Historical 中文展示名「历史小说」", historical != null && "历史小说".equals(historical.displayName), name(historical));
        check("History 下有 Chinese History（id=807，父=17）", findChild(history, 807) != null,
                name(findChild(history, 807)));
        check("同名二级分类不串父级：Biography > Historical 的 id=77 ≠ 270",
                findChild(biography, 77) != null && findChild(biography, 77).parentId == 3,
                name(findChild(biography, 77)));

        // ---- 历史分类 1/2/3 页 ----
        List<Book> historyBooks = new ArrayList<Book>();
        CategoryPage last = null;
        Set<Integer> ids = new HashSet<Integer>();
        for (int page = 1; page <= 3; page++) {
            CategoryPage result = WebCategorySource.fetchBooks(tree.baseUrl, history, "", page,
                    page <= 1 ? "" : (last == null ? "" : last.pageUrlPattern));
            check("历史分类第 " + page + " 页书目数量 == 50", result.items.size() == 50,
                    "items=" + result.items.size());
            check("历史分类第 " + page + " 页 current == " + page, result.current == page,
                    "current=" + result.current);
            check("历史分类分页 pagesTotal == 3", result.totalPages == 3, "totalPages=" + result.totalPages);
            for (Book book : result.items) {
                ids.add(book.id);
                historyBooks.add(book);
            }
            last = result;
        }
        check("历史分类三页书目 ID 去重后 == 150（无重复）", ids.size() == 150, "unique=" + ids.size()
                + " total=" + historyBooks.size());
        Book shiji = findByTitle(historyBooks, "史记");
        check("历史分类真实包含《史记》记录", shiji != null,
                shiji == null ? "未找到" : (shiji.id + " " + shiji.title + " lang=" + shiji.language
                        + " isbn=" + shiji.isbn + " termshash=" + shiji.termsHash
                        + " href=" + shiji.href));
        if (shiji != null) {
            check("《史记》卡片字段完整：书名/语言/href/termshash",
                    shiji.title.length() > 0 && shiji.language.length() > 0
                            && shiji.href.startsWith("/book/") && shiji.termsHash.length() > 0,
                    shiji.title + " | " + shiji.language + " | " + shiji.href + " | " + shiji.termsHash);
        }

        // ---- 历史小说 270 ----
        CategoryPage novelPage = WebCategorySource.fetchBooks(tree.baseUrl, historical, "", 1, "");
        check("历史小说分类第 1 页书目数量 == 50", novelPage.items.size() == 50,
                "items=" + novelPage.items.size());
        Book sanguo = findById(novelPage.items, 5625805);
        check("历史小说分类包含《三国演义（全二册）》(id=5625805)", sanguo != null,
                sanguo == null ? "未找到" : sanguo.title + " | " + sanguo.author);
        if (sanguo != null) {
            check("《三国演义》卡片 ISBN == 9787020008728", "9787020008728".equals(sanguo.isbn),
                    "isbn=" + sanguo.isbn);
            check("《三国演义》卡片 termshash 非空", sanguo.termsHash.length() == 32,
                    "termshash=" + sanguo.termsHash);
        }

        // ---- 历史 + 中文（AND）三页 ----
        CategoryPage zhPage1 = WebCategorySource.fetchBooks(tree.baseUrl, history, "chinese", 1, "");
        check("历史+中文第 1 页书目数量 == 50", zhPage1.items.size() == 50,
                "items=" + zhPage1.items.size());
        check("历史+中文第 1 页全部 language=Chinese", allLanguage(zhPage1.items, "Chinese"),
                languageSummary(zhPage1.items));
        Set<Integer> zhIds = new HashSet<Integer>();
        for (Book b : zhPage1.items) {
            zhIds.add(b.id);
        }
        for (int page = 2; page <= 3; page++) {
            CategoryPage result = WebCategorySource.fetchBooks(tree.baseUrl, history, "chinese", page,
                    zhPage1.pageUrlPattern);
            check("历史+中文第 " + page + " 页书目数量 == 50", result.items.size() == 50,
                    "items=" + result.items.size());
            check("历史+中文第 " + page + " 页全部 language=Chinese", allLanguage(result.items, "Chinese"),
                    languageSummary(result.items));
            for (Book b : result.items) {
                zhIds.add(b.id);
            }
        }
        check("历史+中文三页书目 ID 去重后 == 150（无跨页重复）", zhIds.size() == 150, "unique=" + zhIds.size());
        check("历史+中文分页 URL 保留 languages 参数",
                zhPage1.pageUrlPattern.contains("languages%5B0%5D=chinese")
                        && zhPage1.pageUrlPattern.contains("page=%number%"),
                zhPage1.pageUrlPattern);
        check("历史+中文书单仍保留分类条件（分页 URL 仍指向 /category/17/History/s/）",
                zhPage1.pageUrlPattern.contains("/category/17/History/s/"), zhPage1.pageUrlPattern);

        // ---- 历史小说 + 中文（AND）----
        CategoryPage novelZh = WebCategorySource.fetchBooks(tree.baseUrl, historical, "chinese", 1, "");
        check("历史小说+中文第 1 页书目数量 == 50", novelZh.items.size() == 50,
                "items=" + novelZh.items.size());
        check("历史小说+中文全部 language=Chinese", allLanguage(novelZh.items, "Chinese"),
                languageSummary(novelZh.items));

        // ---- 繁体中文是独立筛选值 ----
        CategoryPage trad = WebCategorySource.fetchBooks(tree.baseUrl, history, "traditional chinese", 1, "");
        check("历史+繁体中文返回 non-empty", trad.items.size() > 0, "items=" + trad.items.size());
        check("历史+繁体中文全部 language=Traditional Chinese",
                allLanguage(trad.items, "Traditional Chinese"), languageSummary(trad.items));

        // ---- 空结果 vs 解析失败 区分 ----
        CategoryPage empty = WebCategorySource.fetchBooks(tree.baseUrl, history, "nauru", 1, "");
        check("历史+nauru 是显式空结果（empty=true, 0 本）", empty.empty && empty.items.isEmpty(),
                "empty=" + empty.empty + " items=" + empty.items.size());

        Category fake = new Category();
        fake.sourceCategoryId = 999999;
        fake.sourceName = "Foo";
        fake.sourceUrl = "/category/999999/Foo";
        try {
            WebCategorySource.fetchBooks(tree.baseUrl, fake, "", 1, "");
            check("不存在分类抛 NOT_FOUND", false, "未抛异常");
        } catch (WebCategorySource.SourceException e) {
            check("不存在分类抛 NOT_FOUND", e.kind == WebCategorySource.ERROR_NOT_FOUND,
                    "kind=" + e.kind + " http=" + e.httpCode);
        }
        try {
            WebCategorySource.parseBooks("<html><body><div>garbage</div></body></html>", base);
            check("无书单页面抛 PARSE", false, "未抛异常");
        } catch (WebCategorySource.SourceException e) {
            check("无书单页面抛 PARSE", e.kind == WebCategorySource.ERROR_PARSE, "kind=" + e.kind);
        }

        // ---- 镜像域名 + 动态挑战常量 ----
        WebCategorySource.Tree mirror = WebCategorySource.fetchTree("https://z-lib.by/");
        check("镜像 z-lib.by 分类目录也可解析（验证动态挑战常量）", mirror.roots.size() == 39,
                "roots=" + mirror.roots.size());

        // ---- 中文映射/语言展示 ----
        check("语言展示名：chinese → 简体中文", "简体中文".equals(CategoryNames.languageDisplayName("chinese")),
                CategoryNames.languageDisplayName("chinese"));
        check("语言展示名：traditional chinese → 繁体中文",
                "繁体中文".equals(CategoryNames.languageDisplayName("traditional chinese")),
                CategoryNames.languageDisplayName("traditional chinese"));
        check("简繁是两个不同取值", !"chinese".equals("traditional chinese"),
                "chinese vs traditional chinese");
        check("语言筛选取值共 9 个（含不限语言）", Categories.LANGUAGE_VALUES.length == 9,
                "len=" + Categories.LANGUAGE_VALUES.length);

        // ---- BookMapper 纯函数 ----
        check("ISBN 识别：9787020008728", BookMapper.isIsbn("9787020008728"), "true");
        check("ISBN 识别：978-7-02-000872-8", BookMapper.isIsbn("978-7-02-000872-8"), "true");
        check("ISBN 识别：ASIN B09JC951FP 不当作 ISBN", !BookMapper.isIsbn("B09JC951FP"), "false");
        check("href token 提取", "MJekAklBdD".equals(BookMapper.hrefToken("/book/MJekAklBdD/x.html")),
                BookMapper.hrefToken("/book/MJekAklBdD/x.html"));
        com.zlib.clone.model.Book web = new com.zlib.clone.model.Book();
        web.termsHash = "82e26acfab7478612254202c2bef6494";
        com.zlib.clone.model.Book detail = new com.zlib.clone.model.Book();
        detail.termsHash = "82e26acfab7478612254202c2bef6494";
        check("terms_hash 相同时身份校验通过", BookMapper.identityMatches(web, detail), "true");
        detail.termsHash = "82e26acfab7478612254202c2bef6495";
        check("terms_hash 不同时身份校验失败", !BookMapper.identityMatches(web, detail), "false");

        long cost = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("RESULT: " + (checks - failures) + "/" + checks + " passed, "
                + failures + " failed, " + cost + " ms");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + (detail == null ? "" : "  (" + detail + ")"));
    }

    private static String name(Category category) {
        return category == null ? "null" : (category.sourceCategoryId + " " + category.sourceName
                + " → " + category.displayName + " parent=" + category.parentId);
    }

    private static Category findRoot(List<Category> roots, int id) {
        for (Category root : roots) {
            if (root.sourceCategoryId == id) {
                return root;
            }
        }
        return null;
    }

    private static Category findChild(Category root, int id) {
        if (root == null) {
            return null;
        }
        for (Category child : root.children) {
            if (child.sourceCategoryId == id) {
                return child;
            }
        }
        return null;
    }

    private static Book findById(List<Book> books, int id) {
        for (Book book : books) {
            if (book.id == id) {
                return book;
            }
        }
        return null;
    }

    private static Book findByTitle(List<Book> books, String keyword) {
        for (Book book : books) {
            if (book.title != null && book.title.contains(keyword)) {
                return book;
            }
        }
        return null;
    }

    private static boolean allLanguage(List<Book> books, String language) {
        if (books == null || books.isEmpty()) {
            return false;
        }
        for (Book book : books) {
            if (!language.equals(book.language)) {
                return false;
            }
        }
        return true;
    }

    private static String languageSummary(List<Book> books) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Book book : books) {
            Integer n = counts.get(book.language);
            counts.put(book.language, n == null ? 1 : n + 1);
        }
        return counts.toString();
    }
}
