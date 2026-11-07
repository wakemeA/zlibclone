package com.zlib.clone.net;

import com.zlib.clone.model.Book;
import com.zlib.clone.model.Category;
import com.zlib.clone.model.CategoryPage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页版「分类」数据源（纯 Java，无第三方依赖，可脱离 Android 在 JVM 上直接联网测试）。
 *
 * 数据链路（2026-11 实测，可复现，详见 README「分类数据源」）：
 * 1) 网页分类目录 https://&lt;host&gt;/categories 是服务端渲染 HTML：
 *    一级分类写在 &lt;h3 class="category-name" data-name data-id&gt; 里，二级分类写在紧随其后的
 *    &lt;li class="subcategory-name" data-name data-type&gt; 里 —— 初始 HTML 即包含全部父子关系，
 *    不需要执行 JavaScript；
 * 2) 网页分类书单 /category/{id}/{slug} 也是服务端渲染，书目在 &lt;z-bookcard&gt; 元素上，
 *    分页参数在页面脚本 pagerOptions 里；语言筛选是 GET 参数 languages[]（与分类是 AND 关系）；
 * 3) 站点对页面有 DiamWall 式反爬：首次返回 503 + 「Checking your browser」挑战页，
 *    需要解一个 SHA-1 proof-of-work（找到 i 使 SHA1(常量+i) 固定两个字节匹配），
 *    然后带上 c_token / c_time / bsrv Cookie 重新请求。本类按同一算法在客户端计算，
 *    不执行网页脚本、不绕过任何登录或付费限制。
 *
 * 注意：网页书籍 ID 与 /eapi 的书籍 ID 相同（实测），但网页 href 中的短 token 与 termshash
 * **不能**当作 /eapi/book/{id}/{hash} 的 hash（实测返回 Invalid hash）；原生详情映射见
 * {@link BookMapper}。
 */
public final class WebCategorySource {

    /** 兜底网页域名：若当前 /eapi base 的网页版不可用，用它再试一次。 */
    public static final String FALLBACK_BASE = "https://z-lib.gl/";

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int MAX_POW_ITERATIONS = 6000000;

    /** 错误分类：界面按它区分「网络失败 / 需要过验证 / 分类不存在 / 页面结构变化」。 */
    public static final int ERROR_NETWORK = 0;
    public static final int ERROR_CHALLENGE = 1;
    public static final int ERROR_NOT_FOUND = 2;
    public static final int ERROR_PARSE = 3;

    /** 分类页数据源异常。 */
    public static class SourceException extends IOException {

        private static final long serialVersionUID = 1L;

        public final int kind;
        public final int httpCode;

        public SourceException(int kind, int httpCode, String message) {
            super(message);
            this.kind = kind;
            this.httpCode = httpCode;
        }
    }

    /** 分类目录（树 + 实际成功使用的 base）。 */
    public static final class Tree {
        public String baseUrl = "";
        public List<Category> roots = new ArrayList<Category>();
    }

    private static final Map<String, Map<String, String>> COOKIE_JAR =
            new HashMap<String, Map<String, String>>();

    private static final Pattern PARENT_PATTERN = Pattern.compile(
            "<h3\\s+class=\"category-name\"([^>]*)>\\s*<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern CHILD_PATTERN = Pattern.compile(
            "<li\\s+class=\"subcategory-name\"\\s+data-name=\"([^\"]*)\"\\s+data-type=\"([^\"]*)\">"
                    + "\\s*<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern ITEM_PATTERN = Pattern.compile(
            "<div\\s+class=\"book-item resItemBoxBooks[^\"]*\"([^>]*)>");
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<z-bookcard\\b([^>]*)>(.*?)</z-bookcard>", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "([A-Za-z_][\\w:-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PAGER_PATTERN = Pattern.compile(
            "pagerOptions\\s*=\\s*\\{(.*?)\\}\\s*;", Pattern.DOTALL);

    private WebCategorySource() {
    }

    // =====================================================================
    // 对外入口
    // =====================================================================

    /**
     * 拉取分类目录。优先用当前 /eapi base 对应的站点，失败后再试 {@link #FALLBACK_BASE}。
     */
    public static Tree fetchTree() throws IOException {
        List<String> bases = candidateBases();
        IOException last = null;
        for (String base : bases) {
            try {
                return fetchTree(base);
            } catch (IOException e) {
                last = e;
                if (!shouldTryNextBase(e)) {
                    throw e;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new SourceException(ERROR_NETWORK, 0, "没有可用的网页服务器地址");
    }

    /**
     * 用指定站点拉取分类目录（不访问 Api，便于 JVM 直接联网验证）。
     */
    public static Tree fetchTree(String baseOverride) throws IOException {
        String html = get(normalizeBase(baseOverride), "categories");
        List<Category> roots = parseTree(html);
        if (roots.isEmpty()) {
            throw new SourceException(ERROR_PARSE, 200, "分类目录为空（页面结构可能变化）");
        }
        Tree tree = new Tree();
        tree.baseUrl = normalizeBase(baseOverride);
        tree.roots = roots;
        return tree;
    }

    /** 拉取分类书单的一页。language 为空串表示不限语言。 */
    public static CategoryPage fetchBooks(String baseUrl, Category category, String language, int page)
            throws IOException {
        return fetchBooks(baseUrl, category, language, page, "");
    }

    /**
     * 拉取分类书单的一页。
     *
     * @param pageUrlPattern 上一页 pagerOptions 给出的 URL 模板（可空）；有则优先使用，
     *                       保证翻页保留页面自己的分类/语言/排序参数
     */
    public static CategoryPage fetchBooks(String baseUrl, Category category, String language, int page,
                                          String pageUrlPattern) throws IOException {
        if (category == null || category.sourceUrl == null || category.sourceUrl.length() == 0) {
            throw new SourceException(ERROR_PARSE, 0, "分类缺少来源链接");
        }
        int targetPage = page < 1 ? 1 : page;
        boolean hasLanguage = language != null && language.trim().length() > 0;
        String url;
        if (targetPage > 1 && pageUrlPattern != null && pageUrlPattern.contains("%number%")) {
            url = normalizeBase(baseUrl) + trimLeadingSlash(pageUrlPattern.replace("%number%",
                    String.valueOf(targetPage)));
        } else {
            if (hasLanguage && isBlank(category.searchBase)) {
                // 一级分类的目录链接是 /category/17（无 slug），筛选表单 action 只能从页面 HTML 里拿；
                // 先取一次「不限语言」首页回填 searchBase（也顺带验证分类本身可访问）。
                fetchBooks(baseUrl, category, "", 1, "");
                if (isBlank(category.searchBase)) {
                    throw new SourceException(ERROR_PARSE, 200, "分类页面没有筛选表单，无法应用语言筛选");
                }
            }
            url = buildBooksUrl(baseUrl, category, language, targetPage);
            if (url.length() == 0) {
                throw new SourceException(ERROR_PARSE, 0, "无法构造分类书单地址");
            }
        }
        String html = getAbsolute(url);
        CategoryPage result = parseBooks(html, baseUrl);
        result.current = result.current > 0 ? result.current : targetPage;
        if (isBlank(category.searchBase) && !isBlank(result.searchBase)) {
            category.searchBase = result.searchBase;
        }
        return result;
    }

    /** 测试/说明用：构造分类书单的 URL（首页/无分页模板时使用）。 */
    public static String buildBooksUrl(String baseUrl, Category category, String language, int page) {
        String base = normalizeBase(baseUrl);
        int targetPage = page < 1 ? 1 : page;
        if (language == null || language.trim().length() == 0) {
            String path = category.sourceUrl;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (targetPage <= 1) {
                return base + path;
            }
            return base + path + "?page=" + targetPage;
        }
        if (isBlank(category.searchBase)) {
            return "";
        }
        // 网页筛选表单的 action 形如 /category/17/History/s/，语言参数名 languages[]（AND 条件）。
        StringBuilder sb = new StringBuilder(base);
        sb.append(trimLeadingSlash(category.searchBase));
        if (!sb.toString().endsWith("/")) {
            sb.append('/');
        }
        sb.append("?languages%5B0%5D=").append(encode(language.trim()));
        sb.append("&page=").append(targetPage);
        return sb.toString();
    }

    /** 把网页书籍的相对 href（/book/xxx/yyy.html）补成绝对地址。 */
    public static String absoluteBookUrl(String baseUrl, String href) {
        if (href == null || href.length() == 0) {
            return "";
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        String base = normalizeBase(baseUrl);
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (href.startsWith("/")) {
            return base + href.substring(1);
        }
        return base + href;
    }

    // =====================================================================
    // HTTP + 反爬挑战
    // =====================================================================

    private static String get(String baseUrl, String path) throws IOException {
        return getAbsolute(normalizeBase(baseUrl) + trimLeadingSlash(path));
    }

    private static String getAbsolute(String url) throws IOException {
        Response first = request(url);
        if (first.code == 200 && !isChallenge(first.body)) {
            return first.body;
        }
        if (isChallenge(first.body) || first.code == 503) {
            // 解 proof-of-work 并带上挑战 Cookie 重试一次。
            solveChallenge(first.body, hostOf(url));
            Response second = request(url);
            if (second.code == 200 && !isChallenge(second.body)) {
                return second.body;
            }
            if (isChallenge(second.body) || second.code == 503) {
                throw new SourceException(ERROR_CHALLENGE, second.code,
                        "网站要求完成访问验证（DiamWall 挑战未通过）");
            }
            throw toHttpError(second);
        }
        throw toHttpError(first);
    }

    private static SourceException toHttpError(Response response) {
        String body = response.body == null ? "" : response.body;
        if (response.code == 404 || body.contains(">Page not found<")
                || body.contains("<title>Page not found")) {
            return new SourceException(ERROR_NOT_FOUND, response.code, "页面不存在（HTTP "
                    + response.code + "）");
        }
        if (response.code == 513 || body.contains("DiamWall")) {
            return new SourceException(ERROR_CHALLENGE, response.code, "网站要求完成浏览器验证");
        }
        return new SourceException(ERROR_NETWORK, response.code, "请求失败（HTTP "
                + response.code + "）");
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Response request(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Accept-Encoding", "identity");
            String cookie = cookieHeader(target.getHost());
            if (cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            storeCookies(target.getHost(), conn);
            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            return new Response(code, body);
        } catch (IOException e) {
            String message = e.getMessage();
            throw new SourceException(ERROR_NETWORK, 0,
                    "网络请求失败：" + (message == null ? e.getClass().getSimpleName() : message));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean isChallenge(String body) {
        if (body == null) {
            return false;
        }
        return body.contains("a0_0x2a54") || body.contains("Checking your browser")
                || body.contains("Verifying your browser") || body.contains("DiamWall");
    }

    /**
     * 解析并解答 DiamWall 的 SHA-1 proof-of-work。
     *
     * 挑战页脚本等价逻辑：
     * <pre>
     * arr = [常量A, 'c_token=', 'array']      // 顺序可能被混淆轮转，轮转次数由脚本里的 0x178 参数决定
     * c = arr[2]; n1 = parseInt(c[0], 16)
     * 找到 i 使 SHA1(c + i) 的字节 [n1] == 0xB0 且 [n1+1] == 0x0B
     * document.cookie = 'c_token=' + c + i   // 注意 arr[0] 自带 '='，这里再拼名字
     * document.cookie = 'c_time=' + 页面耗时秒
     * </pre>
     */
    private static void solveChallenge(String html, String host) throws IOException {
        if (html == null) {
            throw new SourceException(ERROR_CHALLENGE, 503, "缺少访问验证页面内容");
        }
        Matcher arrayMatcher = Pattern.compile("const a0_0x2a54=\\[([^\\]]*)\\]").matcher(html);
        if (!arrayMatcher.find()) {
            throw new SourceException(ERROR_CHALLENGE, 503, "无法识别访问验证参数（a0_0x2a54）");
        }
        List<String> array = new ArrayList<String>();
        Matcher quoted = Pattern.compile("'([^']*)'").matcher(arrayMatcher.group(1));
        while (quoted.find()) {
            array.add(quoted.group(1));
        }
        if (array.size() < 3) {
            throw new SourceException(ERROR_CHALLENGE, 503, "访问验证参数不完整");
        }
        int rotate = 0;
        Matcher rotateMatcher = Pattern.compile("a0_0x2a54,0x([0-9a-fA-F]+)").matcher(html);
        if (rotateMatcher.find()) {
            try {
                rotate = Integer.parseInt(rotateMatcher.group(1), 16) % array.size();
            } catch (NumberFormatException ignored) {
                rotate = 0;
            }
        }
        if (rotate > 0) {
            Collections.rotate(array, -rotate);
        }
        String constant = array.get(2);
        String cookieName = array.get(0);
        if (cookieName.endsWith("=")) {
            cookieName = cookieName.substring(0, cookieName.length() - 1);
        }
        int index = -1;
        try {
            index = Integer.parseInt(constant.substring(0, 1), 16);
        } catch (RuntimeException e) {
            throw new SourceException(ERROR_CHALLENGE, 503, "无法识别访问验证字节位置");
        }
        int solution = solveProofOfWork(constant, index);
        putCookie(host, cookieName, constant + solution);
        // 与浏览器一致：c_time 是解挑战耗时（秒）；服务端只做宽松校验。
        putCookie(host, "c_time", String.format(Locale.US, "%.3f",
                Math.max(1, solution % 3) * 0.25));
    }

    /** 暴力查找满足条件的 i。Java 复用 MessageDigest，实测约 0.2-0.5 秒（~20 万次）。 */
    private static int solveProofOfWork(String constant, int index) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new SourceException(ERROR_CHALLENGE, 0, "当前系统不支持 SHA-1 校验");
        }
        byte[] prefix = constant.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < MAX_POW_ITERATIONS; i++) {
            sha1.reset();
            sha1.update(prefix);
            sha1.update(String.valueOf(i).getBytes(StandardCharsets.UTF_8));
            byte[] digest = sha1.digest();
            if ((digest[index] & 0xFF) == 0xB0 && (digest[index + 1] & 0xFF) == 0x0B) {
                return i;
            }
        }
        throw new SourceException(ERROR_CHALLENGE, 503, "访问验证计算失败（超出上限）");
    }

    private static String cookieHeader(String host) {
        StringBuilder sb = new StringBuilder();
        synchronized (COOKIE_JAR) {
            Map<String, String> jar = COOKIE_JAR.get(host);
            if (jar != null) {
                for (Map.Entry<String, String> entry : jar.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(entry.getKey()).append('=').append(entry.getValue());
                }
            }
        }
        return sb.toString();
    }

    private static void putCookie(String host, String name, String value) {
        if (host == null || host.length() == 0 || name == null || name.length() == 0) {
            return;
        }
        synchronized (COOKIE_JAR) {
            Map<String, String> jar = COOKIE_JAR.get(host);
            if (jar == null) {
                jar = new LinkedHashMap<String, String>();
                COOKIE_JAR.put(host, jar);
            }
            jar.put(name, value);
        }
    }

    private static void storeCookies(String host, HttpURLConnection conn) {
        if (conn == null) {
            return;
        }
        try {
            Map<String, List<String>> fields = conn.getHeaderFields();
            if (fields == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                List<String> values = entry.getValue();
                if (values == null) {
                    continue;
                }
                for (String value : values) {
                    if (value == null) {
                        continue;
                    }
                    int semi = value.indexOf(';');
                    String pair = (semi >= 0 ? value.substring(0, semi) : value).trim();
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    putCookie(host, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        } catch (Throwable ignored) {
            // Cookie 解析失败不影响主流程。
        }
    }

    // =====================================================================
    // HTML 解析
    // =====================================================================

    /** 解析分类目录 HTML（初始 HTML 即包含完整父子树）。 */
    static List<Category> parseTree(String html) throws SourceException {
        List<Category> roots = new ArrayList<Category>();
        if (html == null || html.length() == 0) {
            throw new SourceException(ERROR_PARSE, 200, "分类目录内容为空");
        }
        List<int[]> spans = new ArrayList<int[]>();
        List<String> attrList = new ArrayList<String>();
        List<String> hrefList = new ArrayList<String>();
        List<String> textList = new ArrayList<String>();

        Matcher parent = PARENT_PATTERN.matcher(html);
        while (parent.find()) {
            spans.add(new int[]{parent.start(), parent.end()});
            attrList.add(parent.group(1));
            hrefList.add(decodeHtml(parent.group(2)));
            textList.add(stripTags(parent.group(3)));
        }
        if (spans.isEmpty()) {
            throw new SourceException(ERROR_PARSE, 200, "未找到分类目录结构（页面可能已改版）");
        }
        for (int i = 0; i < spans.size(); i++) {
            int sectionEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : html.length();
            String section = html.substring(spans.get(i)[1], sectionEnd);
            Category root = new Category();
            root.sourceCategoryId = attrInt(attrList.get(i), "data-id", 0);
            // 注意：data-name 属性是全小写（fiction / biography & autobiography），
            // 链接文本才是网站展示的原名（Fiction / Biography & Autobiography），用文本。
            String parentText = textList.get(i);
            root.sourceName = parentText.length() > 0
                    ? parentText : decodeHtml(attrString(attrList.get(i), "data-name", ""));
            root.sourceUrl = hrefList.get(i);
            root.parentId = 0;
            root.bookCount = -1;
            com.zlib.clone.data.CategoryNames.apply(root);

            Matcher child = CHILD_PATTERN.matcher(section);
            while (child.find()) {
                Category sub = new Category();
                String url = decodeHtml(child.group(3));
                String text = stripTags(child.group(4));
                int count = trailingCount(text);
                String childName = text.replaceAll("\\s*\\(\\d[\\d,]*\\)\\s*$", "").trim();
                sub.sourceName = childName.length() > 0 ? childName : decodeHtml(child.group(1));
                sub.type = decodeHtml(child.group(2));
                sub.sourceUrl = url;
                sub.sourceCategoryId = idFromUrl(url);
                sub.parentId = root.sourceCategoryId;
                sub.bookCount = count;
                com.zlib.clone.data.CategoryNames.apply(sub);
                if (sub.sourceCategoryId > 0) {
                    root.children.add(sub);
                }
            }
            if (root.sourceCategoryId > 0) {
                roots.add(root);
            }
        }
        return roots;
    }

    /** 解析分类书单 HTML。 */
    static CategoryPage parseBooks(String html_, String baseUrl) throws SourceException {
        String html = html_ == null ? "" : html_;
        CategoryPage result = new CategoryPage();
        List<Book> books = new ArrayList<Book>();

        // 书单里每本书都包在 <div class="book-item resItemBoxBooks ..."> 里；
        // 空结果页会带一个 hidden 的模板卡（id 有值但 title 为空），必须跳过。
        List<int[]> itemStarts = new ArrayList<int[]>();
        List<String> itemAttrs = new ArrayList<String>();
        Matcher item = ITEM_PATTERN.matcher(html);
        while (item.find()) {
            itemStarts.add(new int[]{item.start(), item.end()});
            itemAttrs.add(item.group(1));
        }
        for (int i = 0; i < itemStarts.size(); i++) {
            String attrs = itemAttrs.get(i);
            if (attrs != null && (attrs.contains("hidden") || attrs.contains("aria-hidden=\"true\""))) {
                continue;
            }
            int end = (i + 1 < itemStarts.size()) ? itemStarts.get(i + 1)[0] : html.length();
            String block = html.substring(itemStarts.get(i)[1], end);
            Matcher card = CARD_PATTERN.matcher(block);
            if (card.find()) {
                Book book = parseCard(card.group(1), card.group(2), baseUrl);
                if (book != null) {
                    books.add(book);
                }
            }
        }
        if (itemStarts.isEmpty()) {
            // 兜底：直接找 z-bookcard（要求有标题，避免把空模板当结果）。
            Matcher card = CARD_PATTERN.matcher(html);
            while (card.find()) {
                Book book = parseCard(card.group(1), card.group(2), baseUrl);
                if (book != null && book.title.length() > 0) {
                    books.add(book);
                }
            }
        }

        boolean emptyMarker = html.contains("class=\"notFound") || html.contains("nothing has been found");
        Matcher pager = PAGER_PATTERN.matcher(html);
        if (pager.find()) {
            String options = pager.group(1);
            result.totalPages = optionInt(options, "pagesTotal", 1);
            result.current = optionInt(options, "pageCurrent", 1);
            result.pageUrlPattern = optionString(options, "baseUrl", "");
        } else {
            result.totalPages = 1;
            result.current = 1;
            result.pageUrlPattern = "";
        }
        result.searchBase = extractSearchBase(html);

        result.items = books;
        if (books.isEmpty()) {
            if (emptyMarker) {
                // 网页明确返回「没有找到」：是合法的空结果，不是解析失败。
                result.empty = true;
                result.totalPages = Math.max(result.current, 1);
                return result;
            }
            String title = pageTitle(html);
            if (title != null && title.toLowerCase(Locale.US).contains("page not found")) {
                throw new SourceException(ERROR_NOT_FOUND, 200, "分类页面不存在");
            }
            // 没有书目、没有空结果标记、也没有分页信息：按页面结构变化处理（不当作空结果）。
            throw new SourceException(ERROR_PARSE, 200,
                    "未解析到书单（页面结构可能变化；bookItem=" + itemStarts.size() + "）");
        }
        if (result.totalPages < result.current) {
            result.totalPages = result.current;
        }
        return result;
    }

    /**
     * 提取分类页里筛选表单的 action（页面实际使用的搜索结果地址），
     * 如 /category/17/History/s/。一级分类的目录链接只有 /category/17，不能自己拼 /s/。
     */
    private static String extractSearchBase(String html) {
        Matcher forms = Pattern.compile("<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (forms.find()) {
            String attrs = forms.group(1);
            if (attrs == null || !attrs.contains("searchForm")) {
                continue;
            }
            Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(attrs);
            if (action.find()) {
                return decodeHtml(action.group(1));
            }
        }
        return "";
    }

    private static Book parseCard(String attrs, String inner, String baseUrl) throws SourceException {
        try {
            Book book = new Book();
            book.id = attrInt(attrs, "id", 0);
            if (book.id <= 0) {
                return null;
            }
            book.isbn = attrString(attrs, "isbn", "");
            book.termsHash = attrString(attrs, "termshash", "");
            String href = attrString(attrs, "href", "");
            book.href = href;
            book.webUrl = absoluteBookUrl(baseUrl, href);
            book.language = attrString(attrs, "language", "");
            book.extension = attrString(attrs, "extension", "");
            book.year = attrString(attrs, "year", "");
            book.filesizeString = attrString(attrs, "filesize", "");
            book.title = decodeHtml(slot(inner, "title"));
            book.author = decodeHtml(slot(inner, "author"));
            Matcher img = Pattern.compile("<img[^>]*?(?:data-src|src)=\"([^\"]*)\"").matcher(inner);
            if (img.find()) {
                String src = decodeHtml(img.group(1)).trim();
                if (src.length() > 0 && !src.startsWith("data:")) {
                    book.cover = src;
                }
            }
            return book;
        } catch (RuntimeException e) {
            throw new SourceException(ERROR_PARSE, 200, "书目字段解析失败：" + e.getMessage());
        }
    }

    private static String slot(String inner, String name) {
        Matcher m = Pattern.compile("<div\\s+slot=\"" + name + "\"[^>]*>(.*?)</div>", Pattern.DOTALL)
                .matcher(inner);
        return m.find() ? m.group(1) : "";
    }

    private static String pageTitle(String html) {
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return m.find() ? stripTags(m.group(1)) : null;
    }

    // =====================================================================
    // 字符串/URL 小工具
    // =====================================================================

    private static int idFromUrl(String url) {
        Matcher m = Pattern.compile("/category/(\\d+)").matcher(url == null ? "" : url);
        return m.find() ? parseInt(m.group(1), 0) : 0;
    }

    private static int trailingCount(String text) {
        Matcher m = Pattern.compile("\\((\\d[\\d,]*)\\)\\s*$").matcher(text == null ? "" : text);
        if (!m.find()) {
            return -1;
        }
        return parseInt(m.group(1).replace(",", ""), -1);
    }

    private static int optionInt(String options, String key, int fallback) {
        Matcher m = Pattern.compile(key + "\\s*:\\s*(\\d+)").matcher(options);
        return m.find() ? parseInt(m.group(1), fallback) : fallback;
    }

    private static String optionString(String options, String key, String fallback) {
        Matcher single = Pattern.compile(key + "\\s*:\\s*'([^']*)'").matcher(options);
        if (single.find()) {
            return single.group(1);
        }
        Matcher doubleQuoted = Pattern.compile(key + "\\s*:\\s*\"([^\"]*)\"").matcher(options);
        if (doubleQuoted.find()) {
            return doubleQuoted.group(1);
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String attrString(String attrs, String name, String fallback) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? decodeHtml(m.group(1)) : fallback;
    }

    private static int attrInt(String attrs, String name, int fallback) {
        return parseInt(attrString(attrs, name, ""), fallback);
    }

    /** 去标签 + HTML 实体解码（&nbsp; 转普通空格）。 */
    static String stripTags(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("<[^>]*>", " ");
        return decodeHtml(text).replace('\u00a0', ' ').trim();
    }

    static String decodeHtml(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = raw.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String entity = raw.substring(i + 1, semi);
            String replacement = null;
            if ("amp".equals(entity)) {
                replacement = "&";
            } else if ("lt".equals(entity)) {
                replacement = "<";
            } else if ("gt".equals(entity)) {
                replacement = ">";
            } else if ("quot".equals(entity)) {
                replacement = "\"";
            } else if ("apos".equals(entity) || "#39".equals(entity)) {
                replacement = "'";
            } else if ("nbsp".equals(entity)) {
                replacement = " ";
            } else if (entity.startsWith("#")) {
                try {
                    int code = entity.startsWith("#x") || entity.startsWith("#X")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    replacement = new String(Character.toChars(code));
                } catch (RuntimeException ignored) {
                    replacement = null;
                }
            }
            if (replacement != null) {
                sb.append(replacement);
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略关闭异常。
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static String normalizeBase(String base) {        String value = (base == null) ? "" : base.trim();
        if (value.length() == 0) {
            value = FALLBACK_BASE;
        }
        if (!value.toLowerCase(Locale.US).startsWith("http://")
                && !value.toLowerCase(Locale.US).startsWith("https://")) {
            value = "https://" + value;
        }
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return value;
    }

    private static String trimLeadingSlash(String path) {
        String value = (path == null) ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> candidateBases() {
        List<String> bases = new ArrayList<String>();
        try {
            String base = Api.get().base();
            if (base != null && base.length() > 0) {
                bases.add(normalizeBase(base));
            }
        } catch (Throwable ignored) {
            // Api 不可用时使用兜底域名。
        }
        if (bases.isEmpty() || !sameHost(bases.get(0), FALLBACK_BASE)) {
            bases.add(normalizeBase(FALLBACK_BASE));
        }
        return bases;
    }

    private static boolean sameHost(String left, String right) {
        return hostOf(left).equalsIgnoreCase(hostOf(right));
    }

    private static boolean shouldTryNextBase(IOException error) {
        if (!(error instanceof SourceException)) {
            return true;
        }
        int kind = ((SourceException) error).kind;
        // 页面解析失败/分类不存在通常是站点差异，也值得换一个域名再试一次。
        return kind == ERROR_PARSE || kind == ERROR_NOT_FOUND || kind == ERROR_CHALLENGE;
    }
}
