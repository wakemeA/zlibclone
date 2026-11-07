# ZLibClone UI 改版契约（v1.0 → v1.1）

> 风格：暖纸白 + 墨绿。所有颜色/间距/圆角/字号**只能引用已有资源**，禁止在 layout 里写死 `#RRGGBB`。
> 只允许 framework API（`android.*`、`java.*`、`org.json.*`）；禁止 AndroidX / Material / 第三方库。Java 8 语法。
> 项目无 Gradle，构建用 `tools/build.sh`（aapt2 + javac + d8 + apksigner）。**本会话 bash 不可用**，不要尝试编译，写完由 Lead 统一构建。

## 0. 文件归属（只改自己的文件，避免冲突）

| 负责人 | 文件 |
|---|---|
| Lead | `res/values/*`、`res/color/*`、`res/drawable/*`、`activity_main.xml`、`MainActivity.java`、`Ui.java`、`ImageLoader.java`、`view_reader.xml`、`ReaderView.java`、`activity_book_detail.xml`、`BookDetailActivity.java`、`activity_login.xml`、`LoginActivity.java`、`activity_splash.xml`、`SplashActivity.java`、`activity_reader.xml`、`ReaderActivity.java` |
| 队友 A（library） | `view_library.xml`、`row_book_card.xml`、`LibraryView.java`、`BookAdapter.java` |
| 队友 B（mybooks） | `view_mybooks.xml`、`row_book_list.xml`、`MyBooksView.java` |
| 队友 B（profile） | `view_profile.xml`、`ProfileView.java`、`dialog_server.xml`、`dialog_language.xml` |

**不要修改** `res/values/strings.xml`、`colors.xml`、`dimens.xml`、`styles.xml`、`themes.xml`、`drawable/`、`MainActivity.java`、`Ui.java`、其它人负责的页面文件。缺少文案就复用已有 key（见 §6），不要新增 key。

## 1. 设计 token（已创建，直接引用）

颜色 `@color/*`：`paper`(页面底 #FAF8F3)、`card`(#FFFFFF)、`ink`(正文 #222822)、`ink_secondary`(辅助 #5F685F)、`primary`(#2F5D4D)、`primary_pressed`、`selected_bg`(#E8F0EA)、`divider`(#E6E2D8)、`danger`(#B3564A)、`skeleton`、`cover_placeholder`、`on_primary`、`transparent`。

尺寸 `@dimen/*`：`space_4/8/12/16/20/24/32`、`screen_margin`(20dp)、`radius_card`(16dp)、`radius_control`(12dp)、`radius_chip`(8dp)、`touch_min`(48dp)、`nav_height`、`icon_nav`、`icon_action`、`cover_width_list`、`cover_height_list`。

文字样式 `@style/TextAppearance.ZL.*`：`PageTitle`(26sp bold)、`Section`(16sp bold)、`Body`(15sp)、`BodySecondary`(14sp)、`Caption`(12sp)；按钮样式 `@style/Widget.ZL.PrimaryButton`、`Widget.ZL.GhostButton`、`Widget.ZL.Segment`（分段栏用，选中态靠 `View.setSelected(true)` + `bg_segment_selector` + `@color/segment_text`）、`Widget.ZL.SettingRow`。

可绘制 `@drawable/*`：`bg_card`、`bg_search`、`bg_primary_button`、`bg_ghost_button`、`bg_segment_selector`、`bg_skeleton`、`bg_cover_placeholder`、`bg_pill`、`bg_avatar`、`bg_nav_selected`、`bg_reader_exit`；图标 `ic_tab_*`、`ic_search`、`ic_clear`、`ic_more`、`ic_chevron_right`、`ic_book`、`ic_back`、`ic_refresh`、`ic_download`（线性，`ImageView` 用 `android:tint` 上色）。

## 2. 共享 Java API（Lead 已实现，直接调用）

```java
// com.zlib.clone.ui.Ui
Ui.init(Context); Ui.dp(Context,float); Ui.toast(Context,String); Ui.toast(Context,int);
String Ui.errorText(Throwable);          // 已把 ApiException → 中文文案
Ui.display(View,boolean);
boolean Ui.handleAuthError(Activity, Throwable);  // 401/403 → toast + 跳登录页，返回 true 时不要再弹错
Ui.confirm(Activity, String title, String message, String positive, Runnable onOk);
String Ui.text(String v, String fallback);

// com.zlib.clone.ui.MainActivity
void switchToLibraryTab();               // 空态「去书库」用
void setNavVisible(boolean);             // 阅读沉浸态用，页面不用调

// com.zlib.clone.ui.BookDetailActivity
static void open(android.app.Activity from, com.zlib.clone.model.Book book);

// com.zlib.clone.data.Prefs（只读：不要改）
Prefs.isLoggedIn(); Prefs.userId(); Prefs.userKey(); Prefs.host(); Prefs.language();

// com.zlib.clone.data.Task  后台执行，回调主线程
Task.run(Task.Job<T>, Task.Ok<T>, Task.Err);

// com.zlib.clone.net.Api（全部阻塞，必须包在 Task.run 里）
Api.get().mostPopular(int page) / recentlyAdded(int page) / recommended(int page)
        / search(String query, int page)
        / saved(int page) / downloaded(int page)
        / book(int id,String hash) / formats(int id,String hash) / downloadInfo(int id,String hash)
        / save(int id) / unsave(int id) / deleteDownloaded(int id)
        / profile() / headers() / absolute(String) / base()
// 列表返回 com.zlib.clone.model.Page<Book>：items / current / totalPages / totalItems / hasMore()
// Book：id title author cover hash extension filesizeString year language description
//       readOnlineUrl readOnlineAvailable isUserSavedBook dateDownload
// 401/403 抛 ApiException(httpCode=401/403)；其它错误 Ui.errorText(e) 可读。
```

所有 View 子类构造都要支持 `(Context)` 与 `(Context, AttributeSet)`；Activity 通过 `(MainActivity) getContext()` 取到宿主，回调里先判 `activity.isFinishing()`。

## 3. 页面结构与 id 契约（**必须逐字一致**）

### 3.1 书库（队友 A）—— `view_library.xml` + `LibraryView.java` + `row_book_card.xml` + `BookAdapter.java`

```
LinearLayout ll_library_root（vertical，bg @color/paper，fitsSystemWindows=true）
├── TextView tv_library_title（"@string/library_title"，@style/TextAppearance.ZL.PageTitle，margin 20/20/0）
├── LinearLayout ll_library_search_box（@drawable/bg_search，height 48dp，marginH 20，marginTop 16，gravity center_vertical，paddingH 12）
│   ├── ImageView ic_search（20dp，tint ink_secondary）
│   ├── EditText et_library_search（weight1，bg transparent，hint @string/search_hint，singleLine，imeOptions actionSearch，textSize 15sp，textColor ink）
│   ├── ImageButton btn_library_clear（40dp，bg transparent，src ic_clear，tint ink_secondary，contentDescription @string/search_clear，默认 gone）
│   └── TextView btn_library_search（"@string/search_submit"，minHeight 48dp，paddingH 8，textColor primary，textStyle bold，gravity center）
├── LinearLayout ll_library_segments（marginH 20，marginTop 16，height 36dp，orientation horizontal）
│   ├── TextView tv_seg_popular / tv_seg_recent / tv_seg_liked（各 weight1，style Widget.ZL.Segment）
├── FrameLayout fl_library_content（weight1，marginTop 12）
│   ├── GridView gv_library（numColumns 2，paddingH 20，paddingBottom 20，clipToPadding false，verticalSpacing/horizontalSpacing 12，scrollbars none）
│   ├── LinearLayout ll_library_skeleton（gone；2 行×2 个 View，bg @drawable/bg_skeleton，高度 220dp/个，间距 12，marginH 20，marginTop 12）
│   ├── LinearLayout ll_library_empty（gone，gravity center，padding 32，vertical）
│   │   ├── ImageView（40dp，tint divider，src ic_book）
│   │   ├── TextView tv_library_empty_title（@style/TextAppearance.ZL.Section，marginTop 16，gravity center）
│   │   ├── TextView tv_library_empty_desc（@style/TextAppearance.ZL.Caption，marginTop 8，gravity center）
│   │   └── TextView btn_library_empty_action（style Widget.ZL.GhostButton，marginTop 24，minWidth 120dp）
│   ├── LinearLayout ll_library_error（gone，同空态结构）
│   │   ├── ImageView（40dp，tint divider，src ic_refresh 或 ic_book）
│   │   ├── TextView tv_library_error_title
│   │   ├── TextView tv_library_error_desc
│   │   └── TextView btn_library_error_retry（GhostButton）
│   └── TextView tv_library_loading_more（gone，caption，gravity center，padding 12：@string/loading_more）
```

- `row_book_card.xml`：卡片（bg_card，radius_card，padding 8）+ `ImageView iv_card_cover`（宽 match，高由代码按列宽算 3:4，bg_cover_placeholder，scaleType centerCrop，contentDescription @string/cover_desc）+ `TextView tv_card_title`（Body，maxLines 2，ellipsize end，marginTop 8）+ `TextView tv_card_author`（Caption，maxLines 1）。
- 分段栏三个 tag：**热门 / 分类 / 猜你喜欢**（v1.1.2 起「分类」替换原「最近上架」）。
  - 热门 → `Api.mostPopular`；猜你喜欢 → `Api.recommended`；默认「热门」。
  - 「分类」：书源没有内容分类字段/接口（列表/详情无 genre/category/subject；分类类端点均 404；
    官方 App 也没有分类浏览；外部 OpenLibrary 按 ISBN 补全实测精确匹配率仅 10%，不可靠）。
    按需求显示「当前书源暂未提供内容分类」+ 缺失字段/接口说明 +「去热门」；**不发起分类请求、不用语言/关键词/示例数据拼凑分类**。
    `最近上架`入口及其请求已移除。
- 搜索：提交（按钮或键盘 actionSearch）→ `Api.search(q, 1)`；搜索态下隐藏分段栏（`GONE`），清空（clear 按钮或 `handleBack()`）→ 恢复分段栏 + 恢复该榜单**原有列表与滚动位置**（把上次榜单数据与 `gv_library.getFirstVisiblePosition()` 存在字段里，不要重新请求）。
- 分页：GridView `OnScrollListener`，最后可见项 + 4 >= total 且 `page.hasMore()` 且未在加载 → 加载下一页并 append。
- 状态：首屏 `ll_library_skeleton`；成功后显示 grid；**空**（榜单无书）用 `ll_library_empty`（标题 @string/library_empty_title、说明 @string/library_empty_desc、按钮 @string/action_refresh 重新请求当前榜单）；**搜索无结果**用 `ll_library_empty`（标题 @string/search_empty_title、说明 @string/search_empty_desc、按钮 @string/search_no_result_action → 清空搜索）；**失败**用 `ll_library_error` + 重试。
- **旧响应不得覆盖新结果**：字段 `int requestToken`，每次发起请求 `token = ++requestToken`，回调里 `if (token != requestToken) return;`；切榜/提交搜索/清空搜索都要递增。
- 点击卡片 → `BookDetailActivity.open(activity, book)`。
- 无网/401：`Ui.handleAuthError` 优先；失败时按错误态展示。
- `public boolean handleBack()`：搜索态返回 true 并清空搜索；否则 false。

### 3.2 我的书籍（队友 B）—— `view_mybooks.xml` + `MyBooksView.java` + `row_book_list.xml`

```
LinearLayout ll_mybooks_root（vertical，paper，fitsSystemWindows=true）
├── TextView tv_mybooks_title（"@string/mybooks_title"，PageTitle，margin 20/20/0）
├── LinearLayout ll_mybooks_segments（marginH 20，marginTop 16，height 36dp）
│   ├── TextView tv_seg_saved / tv_seg_downloaded（weight1，style Widget.ZL.Segment）
├── FrameLayout fl_mybooks_content（weight1，marginTop 12）
│   ├── ListView lv_mybooks（paddingH 20，paddingBottom 20，clipToPadding false，divider none，scrollbars none）
│   ├── LinearLayout ll_mybooks_skeleton（gone；4 行卡片占位：bg_card + 内部 76dp bg_skeleton 缩略图 + 两行文字条）
│   ├── LinearLayout ll_mybooks_empty（gone；图标 + tv_mybooks_empty_title + tv_mybooks_empty_desc + btn_mybooks_empty_action「去书库」）
│   ├── LinearLayout ll_mybooks_error（gone；图标 + tv_mybooks_error_title + tv_mybooks_error_desc + btn_mybooks_error_retry「重试」）
│   └── TextView tv_mybooks_loading_more（gone，caption 居中）
```
- `row_book_list.xml`：bg_card + radius_card + padding 12，horizontal：`ImageView iv_row_cover`（56×76，bg_cover_placeholder，contentDescription @string/cover_desc）+ 中间 vertical（`tv_row_title` Body maxLines 1 ellipsize、`tv_row_author` Caption marginTop 2、`tv_row_status` Caption textColor primary marginTop 4）+ 右侧 `ImageButton btn_row_more`（48×48，bg transparent，src ic_more，contentDescription @string/item_more）。
- 两个分段：收藏 → `Api.saved(page)`；已下载 → `Api.downloaded(page)`；默认收藏。
- 点击条目 → `BookDetailActivity.open(activity, book)`。
- 「更多」点击 → `AlertDialog` 列表：收藏页 `@string/item_unsave` / 下载页 `@string/item_delete_download`；选中后 `Ui.confirm(...)`（标题/消息用 `@string/confirm_unsave_title`/`@string/confirm_unsave_message`、`@string/confirm_delete_title`/`@string/confirm_delete_message`，消息用 `String.format(..., book.title)`）→ `Api.unsave(id)` / `Api.deleteDownloaded(id)` → 成功后从本地 list 移除并刷新列表，失败 toast。
- 空态文案：收藏用 `@string/mybooks_empty_saved_title`/`_desc`，已下载用 `@string/mybooks_empty_downloaded_title`/`_desc`；按钮「去书库」→ `((MainActivity) getContext()).switchToLibraryTab()`。
- 失败态：`ll_mybooks_error` + 重试。
- 分页/旧响应保护同书库（token + 滚动加载）。
- 「已下载」条目的本地文件：当前项目只能拿到服务端的已下载列表，没有本地文件路径 → 点击仍进详情页（边界，报告里注明）。

### 3.3 我的（队友 B）—— `view_profile.xml` + `ProfileView.java` + `dialog_server.xml` + `dialog_language.xml`

```
ScrollView sv_profile_root（bg paper，fillViewport）
└── LinearLayout（vertical，paddingBottom 32）
    ├── TextView tv_profile_title（"@string/profile_title"，PageTitle，margin 20/20/0）
    ├── LinearLayout ll_account_card（@style/Widget.ZL.Card，marginH 20，marginTop 16）
    │   ├── FrameLayout fl_account_body
    │   │   ├── LinearLayout ll_account_content（gone 初始）
    │   │   │   ├── TextView tv_account_avatar（44dp，bg @drawable/bg_avatar，gravity center，primary，bold）
    │   │   │   ├── vertical：tv_account_name（Section）、tv_account_email（Caption，maxLines 1 ellipsize）
    │   │   │   └── TextView tv_account_membership（bg_pill，paddingH 8，paddingV 4，Caption，primary）
    │   │   ├── LinearLayout ll_account_skeleton（骨架：44dp 圆 + 两行条）
    │   │   ├── LinearLayout ll_account_error（gone：tv_account_error + btn_account_retry）
    │   │   └── LinearLayout ll_account_logged_out（gone：tv_account_not_logged_in、「去登录」btn_account_login）
    │   └── TextView btn_account_refresh（右上角小字「刷新」，minHeight 48dp）
    ├── TextView tv_quota_section（@string/quota_section，Section，marginH 20，marginTop 24）
    ├── LinearLayout ll_quota_card（Widget.ZL.Card，marginH 20，marginTop 12）
    │   ├── TextView tv_quota_number（PageTitle 缩小到 22sp 可用 Section；主色）
    │   ├── TextView tv_quota_used（Caption）
    │   ├── ProgressBar pb_quota（style="?android:attr/progressBarStyleHorizontal"，height 6dp，progressTint primary，progressBackgroundTint divider）
    │   └── TextView btn_quota_refresh（「刷新」小字）
    ├── TextView tv_settings_section（@string/settings_section，Section，marginH 20，marginTop 24）
    ├── LinearLayout ll_settings_card（Widget.ZL.Card，padding 0，marginH 20，marginTop 12）
    │   ├── LinearLayout rl_setting_server（@style/Widget.ZL.SettingRow）：vertical（tv_setting_server「服务器地址」Body + tv_setting_server_desc Caption）+ tv_setting_server_value（Caption，primary）+ ImageView（chevron）
    │   ├── View（divider 1dp，marginStart 20）
    │   └── LinearLayout rl_setting_language（同结构：tv_setting_language / tv_setting_language_desc / tv_setting_language_value）
    └── TextView tv_profile_logout（@string/logout，textColor @color/danger，gravity center，marginTop 32，minHeight 48dp，padding，background selectableItemBackground）
```

- 账户数据：`Api.get().profile()`；`User` 字段：`name/email/isPremium(1=会员)/downloadsToday/downloadsLimit/isConfirmed`。**字段语义（逆向确认）**：`downloads_today` 是今日已用次数，`downloads_limit` 是每日上限 → 额度卡主文案 `@string/quota_remaining`（剩余 = limit - today，`String.format`），辅助 `@string/quota_used`；`limit<=0` 时显示 `@string/quota_unknown`、进度条 gone。
- 首屏骨架 `ll_account_skeleton`；刷新时**保留已加载内容**并在按钮上显示「刷新中…」（`btn_account_refresh.setEnabled(false)` / 文案切换，完成后恢复），失败用整卡错误态或 toast（首次失败用错误态 + 重试，已有内容时仅 toast + `@string/refresh_failed`）。
- 未登录或 401：显示 `ll_account_logged_out`（`tv_account_not_logged_in` = `@string/account_not_logged_in`，说明 `account_not_logged_in_desc` 可选，`btn_account_login` → `LoginActivity`，`FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK`），额度/设置区按未登录处理（额度显示 unknown，服务器/语言仍可改）。
- 设置行 → `AlertDialog`：
  - 服务器地址：`dialog_server.xml` 只含 `EditText et_dialog_server`（预填 `Prefs.host()`）+ 错误 `TextView tv_dialog_error`；保存时校验 `startsWith("http://")||startsWith("https://")`，不合法显示 `@string/dialog_server_invalid` 不关闭；合法则后台 `Api.get().probeHost(addr)`，成功 `Api.get().setBase(resolved)` + toast `@string/setting_saved` + 刷新账户/书库（调用 `((MainActivity) getContext())` 无此能力则只更新值），失败 toast `@string/refresh_failed` 并保持弹窗。
  - 内容语言：`dialog_language.xml`（TextView 说明 + RadioGroup/ListView 选项 en/zh/ru/es/fr/de，当前值 `Prefs.language()` 选中）；确认后 `Api.get().setLanguage(lang)` + toast 已保存。
- 「退出登录」二次确认（`@string/logout_confirm_title/message/action`）→ `Api.get().logout()` → 跳 `LoginActivity`（NEW_TASK|CLEAR_TASK）。
- 所有 `Task.run` 回调判 `isFinishing()`；颜色全部走 `@color`。

## 4. 通用要求

- 一律用 `@string/*` 文案，不硬编码中文（调试 toast 可例外）。
- 所有可点击控件 ≤48dp 高或补 `minHeight/minWidth=@dimen/touch_min`；ImageView 加 `contentDescription`。
- 深色大字号/长文本：标题 `maxLines=1 ellipsize=end`；邮箱 `maxLines=1`；书名最多 2 行。
- 键盘：搜索框 `imeOptions=actionSearch`；`MainActivity` 已在 Manifest 配 `adjustResize`。
- 不用 `ConstraintLayout`（避免额外依赖）；用 LinearLayout/FrameLayout/ScrollView/GridView/ListView。
- 不新增 drawable/color/string 资源（缺少就用已有组合，如 `bg_pill` + padding 做 chip）。
- 完成后 send_message 给 Lead：文件清单 + 行数 + 3 个注意点（含你保留/省略的交互）。
