# ZLibClone —— 复刻项目接口契约（SPEC）

> 目标：用**纯 Java + Android framework（零第三方依赖）**重写 Z-Library 客户端，最终产出可安装 APK。
> 参考实现：本目录上一级 `jadxout/sources/`（原始 APK 反编译）、`apkwork/reports/*.md`。
> 构建方式：`tools/build.sh`（aapt2 + javac + d8 + zipalign + apksigner），**不使用 Gradle、不使用 AndroidX**。
> 语言级别：Java 8 语法（不要 lambda 之外的新语法，可用 lambda）；minSdk 21 / targetSdk 33。

## 0. 目录与归属

```
zlibclone/app/src/main/
├── AndroidManifest.xml            [UI 负责]
├── java/com/zlib/clone/
│   ├── App.java                   [CORE 负责]
│   ├── net/Api.java               [CORE 负责]
│   ├── net/ApiException.java      [CORE 负责]
│   ├── model/Book.java            [CORE 负责]
│   ├── model/User.java            [CORE 负责]
│   ├── model/Format.java          [CORE 负责]
│   ├── model/DownloadInfo.java    [CORE 负责]
│   ├── model/Page.java            [CORE 负责]
│   ├── data/Prefs.java            [CORE 负责]
│   └── data/Task.java             [CORE 负责]
│   └── ui/**                      [UI 负责]
└── res/**                         [UI 负责]
```

只允许 framework API（`android.*`、`java.*`、`org.json.*`、`javax.net.ssl.*`）。禁止 AndroidX / Material / OkHttp / Gson。

## 1. CORE 必须实现的公开 API（UI 只依赖这些）

### 1.1 `com.zlib.clone.data.Prefs`

```java
public final class Prefs {
    public static void init(Context c);
    public static boolean isLoggedIn();          // userId>0 && userKey non-empty
    public static int userId();
    public static String userKey();
    public static void setUser(User u);          // 保存 id/remix_userkey
    public static void clearUser();
    public static String host();                 // 形如 "https://zlibrary.se/"，永远以 / 结尾
    public static void setHost(String host);     // 内部规范化补 scheme 和结尾 /
    public static String language();             // 默认 "en"
    public static void setLanguage(String lang);
}
```

### 1.2 `com.zlib.clone.data.Task`

```java
public final class Task {
    public interface Job<T> { T run() throws Exception; }
    public interface Ok<T>  { void ok(T v); }
    public interface Err    { void err(Exception e); }
    public static <T> void run(Job<T> job, Ok<T> ok, Err err); // 后台线程执行，回调在主线程
}
```

### 1.3 `com.zlib.clone.net.ApiException`

```java
public class ApiException extends Exception {
    public final int httpCode;          // 0 表示网络层异常
    public final String serverError;    // 服务端 JSON 的 "error" 字段，可为 null
    public ApiException(int httpCode, String message, String serverError);
}
```

### 1.4 `com.zlib.clone.net.Api`（单例，方法全部**阻塞**，必须在 `Task.run` 里调）

```java
public final class Api {
    public static Api get();
    public void setBase(String baseUrl);        // 如 "https://zlibrary.se/"；同时写 Prefs.host()
    public String base();
    public void setLanguage(String lang);       // 写 Prefs + 影响请求头

    // 探活：给 "zlibrary.se" 或 "https://zlibrary.se/" 或 onion 地址，
    // 返回可用时规范化后的 base（带 scheme 和结尾 /），不可用返回 null。
    // 规则：http(s)://host/eapi/info/ok 返回 2xx 且 JSON success==1 → 可用。
    public String probeHost(String host);

    // 认证
    public User login(String email, String password) throws IOException, ApiException;
    public User profile() throws IOException, ApiException;
    public void logout();                       // 清 Prefs

    // 书目（page 从 1 开始）
    public Page<Book> mostPopular(int page)  throws IOException, ApiException;
    public Page<Book> recentlyAdded(int page)throws IOException, ApiException;
    public Page<Book> recommended(int page)  throws IOException, ApiException;
    public Page<Book> search(String query, int page) throws IOException, ApiException;
    public Page<Book> saved(int page)        throws IOException, ApiException;
    public Page<Book> downloaded(int page)   throws IOException, ApiException;

    public Book book(int id, String hash) throws IOException, ApiException;
    public List<Format> formats(int id, String hash) throws IOException, ApiException;
    public DownloadInfo downloadInfo(int id, String hash) throws IOException, ApiException;
    public boolean save(int id) throws IOException, ApiException;
    public boolean unsave(int id) throws IOException, ApiException;
    public boolean deleteDownloaded(int id) throws IOException, ApiException;

    // 给 WebView / DownloadManager 复用的请求头
    public java.util.Map<String,String> headers();
    // 把接口返回的相对路径（如 "/covers/xx.jpg"、"readOnlineUrl"）解析为绝对 URL
    public String absolute(String pathOrUrl);
}
```

### 1.5 模型（UI 只读字段）

```java
package com.zlib.clone.model;

public class Book {
    public int id; public String title, author, cover, hash, extension, filesizeString;
    public String year, language, description, href, dl, preview, readOnlineUrl, dateDownload;
    public long filesize;
    public boolean readOnlineAvailable, isUserSavedBook, sendToEmailAvailable, kindleAvailable;
}

public class User {
    public int id; public String email, name, remixUserkey, kindleEmail, donationsExpire;
    public int downloadsToday, downloadsLimit, donationsActive, isPremium, isConfirmed;
}

public class Format { public int id; public String hash, extension, filesizeString; public long filesize; }

public class DownloadInfo { public String url, description, extension; public boolean allowDownload; }

public class Page<T> {
    public java.util.List<T> items;
    public int current, totalPages, totalItems;
    public boolean hasMore();      // current < totalPages
}
```

### 1.6 网络契约要点（CORE 必须照做，来自逆向）

- base URL 由 `Prefs.host()` 决定；所有请求走 `HttpURLConnection`，15s 超时，`Accept: application/json`。
- 请求头（**全部要带**）：
  `Authorization: aMaaSIA86Uq8mOzt`、`source: android`、
  `remix-userid: <userId>`、`remix-userkey: <userKey>`、
  `Cookie: remix_userId=<userId>; remix_userkey=<userKey>`（Cookie 里是驼峰！）、
  `ANDROID-APP-LANGUAGE: <language>`、`ANDROID-APP-VERSION: 1.11.4`、`appversion: 1.11.4`、
  `ANDROID-OS-VERSION: Build.VERSION.RELEASE`、`ANDROID-MOBILE-VERSION: Build.MODEL`。
- 端点：
  - GET `/eapi/info/ok`（探活，读 `success`）
  - GET `/eapi/book/most-popular?page=n`、`/eapi/book/recently?page=n`、`/eapi/user/book/recommended?page=n`
  - GET `/eapi/book/{id}/{hash}` → `{success,book}`
  - GET `/eapi/book/{id}/{hash}/formats` → `{success,books:[{id,hash,extension,filesize,filesizeString}]}`
  - GET `/eapi/book/{id}/{hash}/file` → `{success,file:{downloadLink,description,extension,allowDownload}}`
  - POST `/eapi/book/search`（form：`message,page`）→ `{success,books,pagination}`
  - GET `/eapi/user/book/saved?page=n`、`/eapi/user/book/downloaded?page=n` → `{success,books,pagination}`
  - GET `/eapi/user/book/{id}/save` → `{success}`；`/eapi/user/book/{id}/unsave`；`/eapi/user/book/{id}/delete`
  - POST `/eapi/user/login`（form：`email,password`）→ `{success,user,error}`；`GET /eapi/user/profile` → `{success,user}`
- JSON 字段陷阱：`Book.isUserSavedBook` 的 JSON 名是 `_isUserSavedBook`；`User.isConfirmed` 的 JSON 名是 `confirmed`；`Book.dateDownload` 是 `date_download`；分页 `{limit,current,before,next,total_items,total_pages}` 且 `before/next` 可能是 `false` → 解析时必须容错（用 `optInt` 前先判断类型）。
- 401/403 → 抛 `ApiException` 并让上层回登录页；400-500 → 取 JSON `error` 字段放进 `serverError`。
- `headers()` 返回给 WebView/DownloadManager 用的不可变 map（同上 header 集合）。

## 2. UI 必须实现的界面（纯 framework，`android.app.Activity`）

| 类 | 职责 |
|---|---|
| `ui/SplashActivity` | 启动：若已登录 → Main；否则 Login。启动时后台探活 `Prefs.host()`，失败则尝试内置域名列表（见下）；显示进度与失败重试。 |
| `ui/LoginActivity` | 邮箱+密码登录；错误提示；成功 → Main。底部提供「服务器地址」输入（默认内置列表第一个可用）。 |
| `ui/MainActivity` | 底部 4 个 tab：Library / My books / Reader / Profile（用 4 个 LinearLayout 按钮 + FrameLayout 容器切换 View，不要用 Fragment）。 |
| `ui/LibraryView` | 顶部搜索框 + 3 个榜单按钮（Most popular / Recently added / You may like）+ ListView（分页：滑到底加载下一页）。点条目 → BookDetailActivity。 |
| `ui/MyBooksView` | 顶部 Saved / Downloaded 切换 + ListView（分页）。点条目 → 详情；长按 → 删除（Saved 用 unsave，Downloaded 用 delete）。 |
| `ui/ProfileView` | 显示用户信息（邮箱/额度）、服务器地址（可改）、语言、登出。 |
| `ui/ReaderView` | WebView：加载上次阅读 URL（`readOnlineUrl`），带 Cookie（CookieManager + headers）。 |
| `ui/BookDetailActivity` | 展示该书记录字段、封面（简单加载）、收藏/取消、下载（弹格式选择）、在线阅读（跳 Reader tab 或新 Activity）。 |
| `ui/BookAdapter` | ListView 适配器（标题/作者/格式/大小/封面缩略图）。 |
| `ui/ImageLoader` | 极简异步图片加载（LruCache + 线程池，HttpURLConnection 直接下载封面）。 |
| `ui/Ui.java` | 工具：dp()、toast()、loading(ProgressBar) 管理、简单控件创建。 |

要求：所有网络调用必须经 `Task.run`；禁止在主线程发请求；对异常统一 toast。README 里的功能不必 100% 对齐原版，但表内功能必须可用。

## 3. 资源与清单

- `AndroidManifest.xml`：`<application android:label="Z-Lib 复刻版" android:theme="@style/AppTheme" android:usesCleartextTraffic="true" android:allowBackup="false" android:icon="@drawable/ic_launcher">`；
  Activity：Splash(launcher) / Login / Main / BookDetail / Reader，全部 `android:exported` 明确声明（targetSdk 33 强制）。
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`WRITE_EXTERNAL_STORAGE`(maxSdkVersion=28)。
- `res/values/strings.xml`：所有 UI 文案（中文为主，可英文副标题）。
- `res/values/themes.xml`：`AppTheme` parent=`@android:style/Theme.Material.Light.NoActionBar`，主色深蓝。
- `res/layout/`：`activity_login.xml`、`activity_main.xml`、`activity_book_detail.xml`、`activity_reader.xml`、`activity_splash.xml`、`row_book.xml`、`view_library.xml`、`view_mybooks.xml`、`view_profile.xml`、`view_reader.xml`、`dialog_formats.xml`。
- `res/drawable/ic_launcher.png`（已由 Lead 生成，UI 不要覆盖）。

## 4. 内置域名列表（Splash 探活用，UI 侧常量）
```
https://zlibrary.se/  https://1lib.domains/  https://z-library.do/  https://z-library.rs/
https://1lib.sk/  https://z-lib.gs/  https://z-lib.do/  https://z-lib.fm/
https://z-lib.gl/  https://z-lib.gd/  https://z-lib.fo/  https://singlelogin.re/
https://lib-africa.sk/  https://library-latin.sk/  https://library-oceania.sk/  https://library-asia.sk/
```

## 5. 验收
- `tools/build.sh` 零错误产出 `build/ZLibClone.apk`，`apksigner verify` 通过，`aapt2 dump badging` 显示包名/版本/权限正确。
- 代码里不出现 AndroidX import；不出现硬编码的书库内容。
