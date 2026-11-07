package com.zlib.clone.net;

import android.os.Build;

import com.zlib.clone.data.Prefs;
import com.zlib.clone.model.Book;
import com.zlib.clone.model.DownloadInfo;
import com.zlib.clone.model.Format;
import com.zlib.clone.model.Page;
import com.zlib.clone.model.User;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Z-Library eapi 客户端（单例）。
 *
 * 实现约束（对应 SPEC 1.6）：
 * - 只用 java.net.HttpURLConnection + org.json，不依赖 OkHttp/Retrofit/Gson；
 * - 所有方法都是阻塞调用，必须在 {@code Task.run(...)} 的后台线程里调用；
 * - 连接/读取超时各 15s，Accept: application/json；
 * - 每个请求都带原版 HeaderInterceptor 的完整 header 集合（见 {@link #headers()}）；
 * - 401/403 以及其它 4xx/5xx 统一抛 {@link ApiException}，并尽量把 JSON 里的 "error" 抽到 serverError；
 * - 网络层失败（连接失败/超时）包装成 httpCode == 0 的 ApiException（见 SPEC 1.3 的语义）。
 */
public final class Api {

    /** 原版 App 里硬编码的接口密钥（HeaderInterceptor.java:34 / :61）。 */
    private static final String AUTHORIZATION = "aMaaSIA86Uq8mOzt";
    /** 原版 App 版本，用于 ANDROID-APP-VERSION / appversion 头。 */
    private static final String APP_VERSION = "1.11.4";
    /** 连接/读取超时（原版 OkHttp 同为 15s，见 net-api.md §3.1）。 */
    private static final int TIMEOUT_MS = 15000;
    /** 探活专用超时：启动时要轮询十几个域名，单个 15s 会让弱网用户等数分钟。 */
    private static final int PROBE_TIMEOUT_MS = 4000;
    private static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded; charset=UTF-8";
    /** 仅对当前线程生效的超时覆盖（探活用），null 表示用默认 TIMEOUT_MS。 */
    private static final ThreadLocal<Integer> TIMEOUT_OVERRIDE = new ThreadLocal<Integer>();

    /** 单次请求最多跟随的重定向次数；超过视为服务器异常，避免陷入无限跳转环。 */
    private static final int MAX_REDIRECTS = 5;

    /**
     * 响应 Cookie 存储（域名 → name=value）。
     *
     * HttpURLConnection 不会像浏览器那样自动保存 Cookie，而 Z-Library 部分镜像的
     * DiamWall 反爬会给受保护接口（如 /eapi/book/most-popular）返回 307 跳回同一地址，
     * 同时下发 __diamwall Cookie —— 客户端必须保存并在下一次请求回传才能通过；
     * 另外 bsrv Cookie 用于后端服务器粘性。这里按域名保存并自动回传。
     */
    private final Map<String, Map<String, String>> cookieJar =
            new java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>();

    private static final Api INSTANCE = new Api();

    public static Api get() {
        return INSTANCE;
    }

    private Api() {
    }

    // =====================================================================
    // 基础配置
    // =====================================================================

    /** 设置站点地址（内部会规范化补 scheme、补结尾 "/"，并写入 Prefs）。 */
    public void setBase(String baseUrl) {
        Prefs.setHost(baseUrl);
    }

    /** 当前站点地址，永远以 "/" 结尾。 */
    public String base() {
        return Prefs.host();
    }

    /** 设置语言（写入 Prefs，并影响后续请求的 ANDROID-APP-LANGUAGE 头）。 */
    public void setLanguage(String lang) {
        Prefs.setLanguage(lang);
    }

    // =====================================================================
    // 探活
    // =====================================================================

    /**
     * 探测某个域名是否可用。
     *
     * 规则：GET {@code <host>/eapi/info/ok}，HTTP 2xx 且 JSON success == 1 视为可用。
     * 可用返回规范化后的 base（带 scheme 与结尾 "/"），不可用返回 null（不抛异常）。
     */
    public String probeHost(String host) {
        String normalized = normalizeHost(host);
        if (normalized == null) {
            return null;
        }
        try {
            TIMEOUT_OVERRIDE.set(Integer.valueOf(PROBE_TIMEOUT_MS));
            JSONObject json = fetch("GET", normalized + "eapi/info/ok", null);
            if (isSuccess(json)) {
                return normalized;
            }
        } catch (Exception e) {
            // 探测失败：交给上层尝试下一个候选域名。
        } finally {
            TIMEOUT_OVERRIDE.remove();
        }
        return null;
    }

    // =====================================================================
    // 认证
    // =====================================================================

    public User login(String email, String password) throws IOException, ApiException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("email", (email == null) ? "" : email);
        form.put("password", (password == null) ? "" : password);

        JSONObject json = fetch("POST", "/eapi/user/login", form);
        JSONObject userJson = json.optJSONObject("user");
        if (isSuccess(json) && userJson != null) {
            User user = parseUser(userJson);
            Prefs.setUser(user);
            return user;
        }
        throw appError(json, "Login failed");
    }

    public User profile() throws IOException, ApiException {
        JSONObject json = fetch("GET", "/eapi/user/profile", null);
        JSONObject userJson = json.optJSONObject("user");
        if (isSuccess(json) && userJson != null) {
            return parseUser(userJson);
        }
        throw appError(json, "Failed to load profile");
    }

    /** 登出：只清本地凭证（原版也是清 user_id / remix_userkey）。 */
    public void logout() {
        Prefs.clearUser();
    }

    // =====================================================================
    // 书目列表（page 从 1 开始）
    // =====================================================================

    public Page<Book> mostPopular(int page) throws IOException, ApiException {
        return parsePage(fetch("GET", "/eapi/book/most-popular?page=" + validPage(page), null));
    }

    public Page<Book> recentlyAdded(int page) throws IOException, ApiException {
        return parsePage(fetch("GET", "/eapi/book/recently?page=" + validPage(page), null));
    }

    public Page<Book> recommended(int page) throws IOException, ApiException {
        return parsePage(fetch("GET", "/eapi/user/book/recommended?page=" + validPage(page), null));
    }

    public Page<Book> search(String query, int page) throws IOException, ApiException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("message", (query == null) ? "" : query);
        form.put("page", String.valueOf(validPage(page)));
        return parsePage(fetch("POST", "/eapi/book/search", form));
    }

    public Page<Book> saved(int page) throws IOException, ApiException {
        return parsePage(fetch("GET", "/eapi/user/book/saved?page=" + validPage(page), null));
    }

    public Page<Book> downloaded(int page) throws IOException, ApiException {
        return parsePage(fetch("GET", "/eapi/user/book/downloaded?page=" + validPage(page), null));
    }

    // =====================================================================
    // 单书 / 格式 / 下载链接
    // =====================================================================

    public Book book(int id, String hash) throws IOException, ApiException {
        JSONObject json = fetch("GET", bookPath(id, hash), null);
        JSONObject bookJson = json.optJSONObject("book");
        if (bookJson == null && (json.has("id") || json.has("title"))) {
            // 容错：极少数响应可能把书对象直接放在根上。
            bookJson = json;
        }
        if (bookJson != null) {
            return parseBook(bookJson);
        }
        throw appError(json, "Failed to load book");
    }

    public List<Format> formats(int id, String hash) throws IOException, ApiException {
        JSONObject json = fetch("GET", bookPath(id, hash) + "/formats", null);
        checkSuccess(json, "Failed to load formats");

        List<Format> formats = new ArrayList<Format>();
        JSONArray array = json.optJSONArray("books");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    formats.add(parseFormat((JSONObject) item));
                }
            }
        }
        return formats;
    }

    public DownloadInfo downloadInfo(int id, String hash) throws IOException, ApiException {
        JSONObject json = fetch("GET", bookPath(id, hash) + "/file", null);
        checkSuccess(json, "Failed to get download link");

        JSONObject fileJson = json.optJSONObject("file");
        if (fileJson == null) {
            throw appError(json, "Download link is not available");
        }
        DownloadInfo info = parseDownloadInfo(fileJson);
        // downloadLink 通常已是完整 CDN 地址，但相对路径也兼容一下。
        info.url = absolute(info.url);
        return info;
    }

    // =====================================================================
    // 收藏 / 已下载（返回 success == 1 即 true）
    // =====================================================================

    public boolean save(int id) throws IOException, ApiException {
        return isSuccess(fetch("GET", "/eapi/user/book/" + id + "/save", null));
    }

    public boolean unsave(int id) throws IOException, ApiException {
        return isSuccess(fetch("GET", "/eapi/user/book/" + id + "/unsave", null));
    }

    public boolean deleteDownloaded(int id) throws IOException, ApiException {
        return isSuccess(fetch("GET", "/eapi/user/book/" + id + "/delete", null));
    }

    // =====================================================================
    // 复用给 WebView / DownloadManager 的 header 与 URL 工具
    // =====================================================================

    /**
     * 原版 HeaderInterceptor 的完整 header 集合（不可变 Map）。
     * 注意 Cookie 里是驼峰的 remix_userId，而 header 名是 remix-userid。
     */
    public Map<String, String> headers() {
        int userId = Prefs.userId();
        String userKey = Prefs.userKey();
        if (userKey == null) {
            userKey = "";
        }
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("Authorization", AUTHORIZATION);
        map.put("source", "android");
        // 与原版 HeaderInterceptor 对齐：remix 头只在已登录（userkey 非空）时才补；
        // 未登录时发空的 remix-userkey 有可能被服务端当成无效 token 而 401。
        if (userKey.length() > 0) {
            map.put("remix-userid", String.valueOf(userId));
            map.put("remix-userkey", userKey);
        }
        // Cookie：remix 登录态 + 该域名已保存的响应 Cookie（DiamWall 反爬 / bsrv 粘性）。
        map.put("Cookie", cookieHeader(hostOf(base())));
        map.put("ANDROID-APP-LANGUAGE", safe(Prefs.language()));
        map.put("ANDROID-APP-VERSION", APP_VERSION);
        map.put("appversion", APP_VERSION);
        map.put("ANDROID-OS-VERSION", safe(Build.VERSION.RELEASE));
        map.put("ANDROID-MOBILE-VERSION", safe(Build.MODEL));
        return Collections.unmodifiableMap(map);
    }

    /**
     * 把接口返回的相对路径解析成绝对 URL。
     * - "http://…" / "https://…" 原样返回；
     * - "//host/path" 协议相对地址补 https:；
     * - 以 "/" 开头的拼 base（避免出现双斜杠）；
     * - 其它（如 "covers/x.jpg"）直接拼在 base 后面；
     * - null / 空串返回 ""，方便 UI 直接丢给 ImageLoader 做空判断。
     */
    public String absolute(String pathOrUrl) {
        if (pathOrUrl == null) {
            return "";
        }
        String path = pathOrUrl.trim();
        if (path.length() == 0) {
            return "";
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return path;
        }
        if (path.startsWith("//")) {
            return "https:" + path;
        }
        String base = base();
        if (path.startsWith("/")) {
            return base + path.substring(1);
        }
        return base + path;
    }

    // =====================================================================
    // HTTP 实现
    // =====================================================================

    /**
     * 执行一次请求并返回解析后的 JSON 对象。
     *
     * @param method    "GET" 或 "POST"
     * @param pathOrUrl 相对路径（以 / 开头）或完整 URL
     * @param form      POST 的 x-www-form-urlencoded 表单（GET 传 null）
     */
    private JSONObject fetch(String method, String pathOrUrl, Map<String, String> form) throws ApiException {
        byte[] body = null;
        if ("POST".equals(method)) {
            body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        }
        return fetchWithBody(method, pathOrUrl, body);
    }

    /** 与 {@link #fetch(String, String, Map)} 相同，但直接接收请求体（支持 languages[] 这类重复字段）。 */
    private JSONObject fetchWithBody(String method, String pathOrUrl, byte[] initialBody) throws ApiException {
        String url = absolute(pathOrUrl);
        if (url.length() == 0) {
            throw new ApiException(0, "Empty request URL", null);
        }

        String currentMethod = method;
        byte[] body = initialBody;

        String current = url;
        // 记录 (URL + 当时携带的 Cookie)：Cookie 变化后允许再试一次（DiamWall 挑战），
        // 完全相同的请求再次出现则判定为跳转环，立即失败而不是让系统 HTTP 栈盲跟 20 次。
        Set<String> visited = new HashSet<String>();
        int redirects = 0;
        HttpURLConnection conn = null;
        try {
            while (true) {
                URL target = new URL(current);
                String host = hostOf(current);
                String cookieForHost = cookieHeader(host);
                String visitKey = current + "\n" + cookieForHost;
                if (!visited.add(visitKey)) {
                    throw new ApiException(0,
                            "服务器重定向异常（跳转环），请点击重试或更换服务器地址", null);
                }

                conn = (HttpURLConnection) target.openConnection();
                Integer override = TIMEOUT_OVERRIDE.get();
                int timeout = (override != null && override.intValue() > 0) ? override.intValue() : TIMEOUT_MS;
                conn.setConnectTimeout(timeout);
                conn.setReadTimeout(timeout);
                conn.setRequestMethod(currentMethod);
                conn.setUseCaches(false);
                // 自己处理 3xx：必须在跟随前保存 Set-Cookie（DiamWall 反爬挑战依赖它）。
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Encoding", "identity");
                Map<String, String> reqHeaders = new LinkedHashMap<String, String>(headers());
                reqHeaders.put("Cookie", cookieForHost);
                for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(body.length);
                    conn.setRequestProperty("Content-Type", CONTENT_TYPE_FORM);
                }

                conn.connect();
                if (body != null) {
                    OutputStream out = conn.getOutputStream();
                    try {
                        out.write(body);
                        out.flush();
                    } finally {
                        out.close();
                    }
                }

                int code = conn.getResponseCode();
                storeCookies(host, conn);

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.length() == 0) {
                        throw new ApiException(code, "HTTP " + code, null);
                    }
                    if (++redirects > MAX_REDIRECTS) {
                        throw new ApiException(code,
                                "服务器重定向异常（跳转次数过多），请点击重试或更换服务器地址", null);
                    }
                    String next = new URL(target, location).toString();
                    // 301/302/303 语义上会把 POST 变成 GET；307/308 保留方法与请求体。
                    if (code != 307 && code != 308) {
                        currentMethod = "GET";
                        body = null;
                    }
                    current = next;
                    continue;
                }

                InputStream stream = null;
                if (code >= 400) {
                    stream = conn.getErrorStream();
                } else if (code != HttpURLConnection.HTTP_NO_CONTENT) {
                    stream = conn.getInputStream();
                }
                String text = readAll(stream);

                if (code < 200 || code >= 300) {
                    String serverError = errorField(text);
                    String message = (serverError != null && serverError.length() > 0)
                            ? serverError
                            : ("HTTP " + code);
                    throw new ApiException(code, message, serverError);
                }

                String trimmed = (text == null) ? "" : text.trim();
                if (trimmed.length() == 0) {
                    return new JSONObject();
                }
                try {
                    return new JSONObject(trimmed);
                } catch (JSONException e) {
                    throw new ApiException(code, "Invalid JSON response: " + e.getMessage(), null);
                }
            }
        } catch (IOException e) {
            // 网络层异常：httpCode == 0。
            String message = e.getMessage();
            if (message == null || message.length() == 0) {
                message = e.getClass().getSimpleName();
            }
            if (message != null && message.contains("Too many follow-up requests")) {
                message = "服务器重定向异常，请点击重试或更换服务器地址";
            }
            throw new ApiException(0, message, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 当前登录态 + 指定域名已保存 Cookie 组成的 Cookie 头。 */
    private String cookieHeader(String host) {
        int userId = Prefs.userId();
        String userKey = Prefs.userKey();
        StringBuilder sb = new StringBuilder();
        sb.append("remix_userId=").append(userId).append("; remix_userkey=")
                .append(userKey == null ? "" : userKey);
        Map<String, String> jar;
        synchronized (cookieJar) {
            jar = cookieJar.get(host);
        }
        if (jar != null) {
            for (Map.Entry<String, String> entry : jar.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                sb.append("; ").append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return sb.toString();
    }

    /** 保存响应里的 Set-Cookie（同名覆盖），供后续请求回传。 */
    private void storeCookies(String host, HttpURLConnection conn) {
        if (conn == null || host == null || host.length() == 0) {
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
                    addCookie(host, value);
                }
            }
        } catch (Throwable ignored) {
            // Cookie 解析失败不影响主流程。
        }
    }

    private void addCookie(String host, String raw) {
        if (raw == null || raw.length() == 0) {
            return;
        }
        int semi = raw.indexOf(';');
        String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.length() == 0) {
            return;
        }
        synchronized (cookieJar) {
            Map<String, String> jar = cookieJar.get(host);
            if (jar == null) {
                jar = new LinkedHashMap<String, String>();
                cookieJar.put(host, jar);
            }
            jar.put(name, value);
        }
    }

    /** 取 URL 的 host（小写）；解析失败返回 ""。 */
    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = new URL(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String value = entry.getValue();
            sb.append(urlEncode(entry.getKey()));
            sb.append('=');
            sb.append(urlEncode(value == null ? "" : value));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // =====================================================================
    // JSON 解析（全部容错：字段缺失 / 类型不符都不抛）
    // =====================================================================

    private static Page<Book> parsePage(JSONObject json) throws ApiException {
        checkSuccess(json, "Failed to load books");

        List<Book> items = new ArrayList<Book>();
        JSONArray array = (json == null) ? null : json.optJSONArray("books");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    items.add(parseBook((JSONObject) item));
                }
            }
        }

        int current = 1;
        int totalPages = 1;
        int totalItems = items.size();
        JSONObject pagination = (json == null) ? null : json.optJSONObject("pagination");
        if (pagination != null) {
            current = optInt(pagination, "current", 1);
            if (current < 1) {
                current = 1;
            }
            // pagination 里的 before/next 可能是布尔 false 或 null，一律不读；
            // 这里只取 total_pages / total_items，且全部用容错版 optInt。
            totalPages = optInt(pagination, "total_pages", current);
            totalItems = optInt(pagination, "total_items", items.size());
        }
        if (totalPages < 1) {
            totalPages = 1;
        }
        if (totalPages < current) {
            totalPages = current;
        }
        return new Page<Book>(items, current, totalPages, totalItems);
    }

    private static Book parseBook(JSONObject o) {
        Book book = new Book();
        if (o == null) {
            return book;
        }
        book.id = optInt(o, "id", 0);
        book.title = str(o, "title", "");
        book.author = str(o, "author", "");
        book.cover = str(o, "cover", "");
        // 简本可能没有 hash：兜底空串（URL 拼接时按空处理）。
        book.hash = str(o, "hash", "");
        // 详情接口的 terms_hash 与网页 z-bookcard 的 termshash 同值，用于网页书目→原生详情的身份校验。
        book.termsHash = str(o, "terms_hash", "");
        book.isbn = str(o, "isbn", "");
        book.extension = str(o, "extension", "");
        book.filesizeString = str(o, "filesizeString", "");
        book.year = str(o, "year", "");
        book.language = str(o, "language", "");
        book.description = str(o, "description", "");
        book.href = str(o, "href", "");
        book.dl = str(o, "dl", "");
        book.preview = str(o, "preview", "");
        book.readOnlineUrl = str(o, "readOnlineUrl", "");
        book.dateDownload = str(o, "date_download", "");
        book.filesize = lng(o, "filesize", 0L);
        book.readOnlineAvailable = bool(o, "readOnlineAvailable", false);
        // JSON 名是 _isUserSavedBook（下划线前缀）；顺带兼容不带下划线的写法。
        book.isUserSavedBook = bool(o, "_isUserSavedBook", bool(o, "isUserSavedBook", false));
        book.sendToEmailAvailable = bool(o, "sendToEmailAvailable", bool(o, "isSendToEmailAvailable", false));
        book.kindleAvailable = bool(o, "kindleAvailable", false);
        return book;
    }

    private static User parseUser(JSONObject o) {
        User user = new User();
        if (o == null) {
            return user;
        }
        user.id = optInt(o, "id", 0);
        user.email = str(o, "email", "");
        user.name = str(o, "name", "");
        user.remixUserkey = str(o, "remix_userkey", str(o, "remixUserkey", ""));
        user.kindleEmail = str(o, "kindle_email", "");
        user.donationsExpire = str(o, "donations_expire", "");
        user.downloadsToday = optInt(o, "downloads_today", 0);
        user.downloadsLimit = optInt(o, "downloads_limit", 0);
        user.donationsActive = optInt(o, "donations_active", 0);
        user.isPremium = optInt(o, "isPremium", 0);
        // JSON 名是 confirmed（不是 isConfirmed）。
        user.isConfirmed = optInt(o, "confirmed", optInt(o, "isConfirmed", 0));
        return user;
    }

    private static Format parseFormat(JSONObject o) {
        Format format = new Format();
        if (o == null) {
            return format;
        }
        format.id = optInt(o, "id", 0);
        format.hash = str(o, "hash", "");
        format.extension = str(o, "extension", "");
        format.filesizeString = str(o, "filesizeString", "");
        format.filesize = lng(o, "filesize", 0L);
        return format;
    }

    private static DownloadInfo parseDownloadInfo(JSONObject o) {
        DownloadInfo info = new DownloadInfo();
        if (o == null) {
            return info;
        }
        info.url = str(o, "downloadLink", "");
        info.description = str(o, "description", "");
        info.extension = str(o, "extension", "");
        info.allowDownload = bool(o, "allowDownload", false);
        return info;
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private static int validPage(int page) {
        return (page < 1) ? 1 : page;
    }

    private static String bookPath(int id, String hash) {
        return "/eapi/book/" + id + "/" + encodePath(hash);
    }

    /** URL path 片段编码（空格编码成 %20 而不是 +）。 */
    private static String encodePath(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        return urlEncode(trimmed).replace("+", "%20");
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String safe(String value) {
        return (value == null) ? "" : value;
    }

    private static boolean isSuccess(JSONObject o) {
        if (o == null) {
            return false;
        }
        Object value = o.opt("success");
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
        return false;
    }

    private static void checkSuccess(JSONObject json, String fallbackMessage) throws ApiException {
        if (json != null && json.has("success") && !isSuccess(json)) {
            throw appError(json, fallbackMessage);
        }
    }

    private static ApiException appError(JSONObject json, String fallbackMessage) {
        String serverError = errorField(json);
        String message = (serverError != null && serverError.length() > 0) ? serverError : fallbackMessage;
        // HTTP 是 200，但业务层 success != 1：httpCode 记 200，错误信息放 serverError。
        return new ApiException(200, message, serverError);
    }

    private static String errorField(JSONObject json) {
        if (json == null) {
            return null;
        }
        String error = str(json, "error", null);
        if (error == null || error.length() == 0) {
            error = str(json, "message", null);
        }
        return error;
    }

    private static String errorField(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        try {
            return errorField(new JSONObject(trimmed));
        } catch (JSONException e) {
            return null;
        }
    }

    // ---- org.json 容错读值：Boolean/null/数字字符串都不抛异常 ----

    private static int optInt(JSONObject o, String key, int fallback) {
        if (o == null) {
            return fallback;
        }
        Object value = o.opt(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? 1 : 0;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long lng(JSONObject o, String key, long fallback) {
        if (o == null) {
            return fallback;
        }
        Object value = o.opt(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        if (o == null) {
            return fallback;
        }
        Object value = o.opt(key);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
        }
        return fallback;
    }

    /**
     * 容错版字符串读值：注意 org.json 的 optString 对显式 JSON null 会返回 "null"，
     * 这里统一按 fallback 处理。数字/布尔字段转成字符串。
     */
    private static String str(JSONObject o, String key, String fallback) {
        if (o == null) {
            return fallback;
        }
        Object value = o.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return fallback;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return String.valueOf(value);
    }

    /**
     * 探活时对候选域名做规范化（不落库，不动 Prefs）：
     * - 缺 scheme：.onion 补 http://，其它补 https://；
     * - 去掉路径、query、fragment，只保留 scheme + host[:port]；
     * - 结尾统一补 "/"。
     */
    private static String normalizeHost(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim();
        if (host.length() == 0) {
            return null;
        }
        String lower = host.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.endsWith(".onion")) {
                host = "http://" + host;
            } else {
                host = "https://" + host;
            }
            lower = host.toLowerCase(Locale.US);
        }
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        String scheme = host.substring(0, schemeEnd + 3);
        String rest = host.substring(schemeEnd + 3);

        int cut = rest.length();
        int slash = rest.indexOf('/');
        if (slash >= 0 && slash < cut) {
            cut = slash;
        }
        int question = rest.indexOf('?');
        if (question >= 0 && question < cut) {
            cut = question;
        }
        int hash = rest.indexOf('#');
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        rest = rest.substring(0, cut);

        if (rest.length() == 0) {
            return null;
        }
        return scheme + rest + "/";
    }
}
