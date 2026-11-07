# ZLibClone「分类」UI 设计逻辑说明（供独立 review）

- 项目：ZLibClone（Android，纯 Java / framework，当前版本 v1.2.0 / versionCode 7）
- 文档目的：把「书库 → 分类」**当前实际实现**的 UI 结构、交互、状态、数据流与可复现证据整理成自包含说明，交给其他 AI / 设计同学 review
- 编写时间：2026-11-07
- 说明边界：本文是**按现有代码与布局做的静态整理**，不是产品提案。文中第 12 节的「候选问题」来自代码/布局静态分析，**未经真机验证**（本环境没有 Android 运行时/模拟器，无法截图或录屏）。线框图是按布局参数推导的示意图，不是真实截图。

---

## 0. 一句话摘要

「分类」不是独立页面，而是**书库页内容区的一个可切换视图**（与「热门 / 猜你喜欢」并列的分段）。分类视图内部是三层钻取：**一级分类目录 → 该分类的二级分类列表（首行「全部」）→ 分类书单（语言筛选 + 分页 + 书籍网格）**；返回键逐层回退，回到一级目录再按返回会切回「热门」。数据来自网页分类目录，页面级状态分为骨架、列表、网格、空结果、失败五类，其中失败再细分网络失败 / 需要完成网页验证 / 页面结构变化 / 分类不存在。

---

## 1. 背景与目标（review 需要的上下文）

- 书库原先只有「热门 / 分类 / 猜你喜欢」三个分段；v1.1.4 分类段因「/eapi 无分类数据」降级为不可用说明页。
- v1.2.0 改为**网页分类目录 → 原生分类界面**：网站 `/categories` 提供 39 个一级分类、802 个二级分类；分类书单每页 50 本、网站最多 3 页（约 150 本）。
- 产品硬约束（review 时请按这些标准检查 UI 是否表达正确）：
  1. 分类与语言是 **AND**；默认不限语言；切换分类/语言必须重置分页；下一页必须保留分类+语言；
  2. 简体中文（`chinese`）与繁体中文（`traditional chinese`）是**两个独立筛选值**，界面不能把两者混成一个参数；目前 UI 也**没有**「中文（简+繁）」合并入口；
  3. 空结果、网络失败、需要网页验证、页面结构变化、分类不存在**必须是不同状态**，不能统一显示「当前书源暂未提供内容分类」；
  4. 普通用户页面不展示接口名、JSON 字段、解析器堆栈；
  5. 分类浏览最多只能到网站给的 3 页，不得暗示「全库」；
  6. 未映射成功的书可以打开网页详情，但不能假装 App 内原生详情可用。

---

## 2. 全局信息架构

```
底部导航（常驻，MainActivity）
├── 书库（LibraryView，本功能所在）
│   ├── 页面标题「书库」（26sp）
│   ├── 搜索框（48dp，全库搜索用）
│   ├── 分段栏「热门 / 分类 / 猜你喜欢」（36dp）
│   └── 内容区（FrameLayout fl_library_content，weight=1）
│       ├── 热门/猜你喜欢：书封网格 gv_library + 骨架/空/失败
│       └── 分类：CategoryView（运行时动态 add，覆盖整个内容区）
│           ├── L0 一级分类目录
│           ├── L1 二级分类列表
│           └── L2 分类书单（语言筛选 + 分页 + 网格）
├── 我的书籍
├── 阅读
└── 我的
```

关键点：

- 分类视图**只替换书库内容区**；页面标题「书库」、搜索框、分段栏始终在分类视图上方，因此分类页顶部实际有三层 chrome：书库标题 → 搜索框 → 分段栏 → 分类页自己的面包屑/标题/副标题/语言筛选。
- 分类视图由 `LibraryView` 在初始化时 `new CategoryView(context)` 并 `addView` 到 `fl_library_content`（`LibraryView.java:148–155`），默认隐藏；点「分类」分段时显示，其它分段隐藏。
- 分类视图的树只在**第一次**显示时请求（`CategoryView.onShown()`，`CategoryView.java:228–235`）；之后切走再切回**不重新请求**，层级/语言/页/滚动位置都保留在内存里（进程重启后回到 L0）。

---

## 3. 三层页面的线框示意（按布局参数推导，非截图）

### 3.1 L0 一级分类目录

```
┌──────────────────────────────────────────────┐
│ 书库                                   26sp  │  ← 书库页标题（始终在）
│ ┌──────────────────────────────────────────┐ │
│ │ 🔍 搜索书名、作者或 ISBN        搜索     │ │  ← 全库搜索框（始终在）
│ └──────────────────────────────────────────┘ │
│ [  热门  ][  分类  ][ 猜你喜欢 ]              │  ← 分段栏，「分类」选中
│                                              │
│                        网页分类目录（右上，12sp）│  ← back 隐藏时该行只剩右侧标签
│ 分类                                         │  ← 16sp Section
│ 共 39 个一级分类 · 数据来自网页分类目录；     │  ← 12sp Caption，最多 2 行
│ 部分书籍尚未分类，可通过搜索查找。            │
│                                              │
│ 艺术                                 20 类 › │  ← 行：中文名 / 英文原名·数量 / 箭头
│ Arts                                         │
│ 天文学                               12 类 › │
│ Astronomy                                    │
│ 传记与回忆录                         24 类 › │
│ Biography & Autobiography                    │
│ …（共 39 行，垂直滚动）                       │
└──────────────────────────────────────────────┘
```

- 行：`row_category.xml`，左侧两行文本（标题 15sp、副标题 12sp），右侧数量（12sp）+ 16dp 箭头；整行可点。
- 有中文映射的：标题=中文名，副标题=网站英文原名；没有中文映射的：标题=网站原名，副标题隐藏。
- 数量=该一级分类下的二级分类数（`N 类`），不是书目数。
- L0 没有返回按钮、没有语言筛选。

### 3.2 L1 二级分类列表（以「历史」为例）

```
│ ‹ 全部分类                          网页分类目录 │  ← 返回按钮 48dp 高（左侧）
│ 历史                                         │  ← 一级分类中文名 16sp
│ 原名：History · 共 22 个二级分类              │  ← 12sp Caption
│                                              │
│ 全部                                         │  ← 首行：进入该一级分类自身书单
│ 历史                                         │
│ 中国史                               40084 本 ›│
│ Chinese History                              │
│ 世界史                               49216 本 ›│
│ World History                                │
│ …（22 行）                                    │
```

- 固定的第一行「全部」= 该一级分类自身的书单（`Category.sourceUrl = /category/17`）；
- 子分类行：标题=中文名/原名，副标题=有中文名时显示英文原名、否则显示「虚构类 / 非虚构类」（来自网站 `data-type`），右侧=书目数（网站计数，**不随语言筛选变化**）；
- 本层无语言筛选、无网络请求（数据在分类目录树里一次性拿到）。

### 3.3 L2 分类书单（以「历史 → 中国史」为例）

```
│ ‹ 历史                              网页分类目录 │  ← back 文字=上一级一级分类名
│ 历史 / 中国史                                 │  ← 父 / 子；若点的是「全部」则只显示「历史」
│ 第 1/3 页 · 已加载 50 本 · Chinese History    │  ← 分页状态 + 网站原名（有中文映射时）
│ [全部语言] [简体中文] [繁体中文] [英语] …→   │  ← 横向滚动 chips，9 个
│                                              │
│ ┌────────┐ ┌────────┐                        │
│ │ 封面   │ │ 封面   │                        │  ← 复用书库 BookAdapter 卡片
│ │ 书名   │ │ 书名   │                        │     2 列（宽屏 ≥600dp 时 3 列）
│ │ 作者   │ │ 作者   │                        │
│ └────────┘ └────────┘                        │
│ …（每页 50 本，滚动触底自动加载下一页）        │
│              正在加载更多…（底部，加载中）      │
└──────────────────────────────────────────────┘
```

- 标题规则：点二级分类 → `父中文名 / 子中文名`；点「全部」→ 只有一级分类名（**与 L1 标题相同**，只能靠副标题和返回按钮区分）。
- 副标题规则：有数据后显示「第 p/total 页 · 已加载 n 本」，有中文映射时追加「 · 网站原名」；加载中显示「正在加载分类书单…」。
- 语言 chips 仅本层出现，默认选中「全部语言」；切换语言会清空网格、回到骨架、从第 1 页重新请求。
- 书单卡片点击：可映射 → 进原生 `BookDetailActivity`；不可映射 → 弹窗引导到浏览器网页详情（详见 §6.4）。

---

## 4. 视图树与 id 契约（review 可对照布局文件）

### 4.1 `view_categories.xml`（271 行）

```
LinearLayout ll_categories_root（vertical，match_parent，bg @color/paper）
├── LinearLayout ll_cat_breadcrumb（horizontal，marginStart 12，marginTop 4）
│   ├── TextView btn_cat_back（48dp 高，14sp，primary，默认 gone；点=handleBack）
│   ├── View（weight=1 占位）
│   └── TextView tv_cat_source（Caption 12sp，text="网页分类目录"，marginEnd 20）
├── TextView tv_cat_title（Section 16sp bold，marginH 20，marginTop 4，maxLines=1）
├── TextView tv_cat_subtitle（Caption 12sp，marginH 20，marginTop 2，maxLines=2）
├── HorizontalScrollView hsv_cat_languages（marginTop 8，paddingH 20，默认 gone）
│   └── LinearLayout ll_cat_languages（横向，chips 运行时动态添加）
└── FrameLayout fl_cat_content（weight=1，marginTop 8）
    ├── ListView lv_cat_categories（divider 1dp，paddingH 20，paddingBottom 20）
    ├── GridView gv_cat_books（2 列，spacing 12，paddingH 20；默认 gone）
    ├── LinearLayout ll_cat_skeleton（4 个 56dp 骨架条，marginTop 8；默认 gone）
    ├── LinearLayout ll_cat_empty（icon 40dp + Section 标题 + Caption 说明 + GhostButton；默认 gone）
    ├── LinearLayout ll_cat_error（icon 40dp + Section 标题 + Caption 说明 + GhostButton 重试；默认 gone）
    └── TextView tv_cat_loading_more（底部 Caption，默认 gone）
```

### 4.2 `row_category.xml`（53 行）

```
LinearLayout（horizontal，minHeight 48dp，paddingV 12，selectableItemBackground）
├── LinearLayout（weight=1，vertical）
│   ├── TextView tv_row_category_title（Body 15sp，maxLines=1，ellipsize=end）
│   └── TextView tv_row_category_subtitle（Caption 12sp，marginTop 2，maxLines=1）
├── TextView tv_row_category_count（Caption 12sp，marginStart 12）
└── ImageView iv_row_category_chevron（16dp，ic_chevron_right，tint ink_secondary，contentDescription=null）
```

### 4.3 复用组件

- 书单网格直接复用 `BookAdapter` + `row_book_card.xml`（封面 3:4、书名最多 2 行、作者 1 行），与书库热门/搜索卡片完全一致。
- 按钮复用 `@style/Widget.ZL.GhostButton`；标题/正文/说明复用 `TextAppearance.ZL.Section / Body / Caption`。
- 语言 chip 不是 XML、不是 `Widget.ZL.Segment`，而是在 `CategoryView.buildLanguageChips()` 里用 `TextView` 动态创建（详见 §5.3）。

---

## 5. 视觉规格（精确到当前实现）

### 5.1 设计 token 使用

| 项 | 值 | 用在哪 |
|---|---|---|
| 页面底色 | `@color/paper` #FAF8F3 | 分类视图根布局 |
| 主色 | `@color/primary` #2F5D4D | 返回按钮文字、chips 选中文字、GhostButton |
| 正文/辅助 | `@color/ink` #222822 / `@color/ink_secondary` #5F685F | 标题正文 / 副标题、数量、右侧标签、箭头 |
| 分割线 | `@color/divider` #E6E2D8 | 列表 1dp 分割线、空/错态图标 tint |
| 选中底 | `@color/selected_bg` #E8F0EA | chips 选中背景（10dp 圆角） |
| 页边距 | `@dimen/screen_margin` = 20dp | 标题/副标题/列表/网格/chips 左右 |
| 间距 | `@dimen/space_4/8/12/16/20/24/32` | 全部间距 |
| 触控下限 | `@dimen/touch_min` = 48dp | 返回按钮高度、按钮 minHeight |
| 字号 | 标题 16sp bold（Section）/ 正文 15sp / 说明 12sp | 见各层 |
| 圆角 | 卡片 16dp（复用书库卡片），chips 10dp | — |

所有颜色均走 `@color/*`，布局里没有硬编码色值。

### 5.2 关键尺寸与对齐

- 分类视图整体左右内边距 20dp；面包屑行 `marginStart=12dp`，back 按钮自身 `paddingStart=8dp`，所以**返回文字视觉左缘=20dp**，与标题/副标题对齐；右侧「网页分类目录」`marginEnd=20dp`。
- 面包屑行高：有返回按钮时 = 48dp；L0 无返回按钮时仅右侧 Caption（≈16dp + 4dp marginTop）→ **L0 与 L1/L2 的头部高度相差约 32dp，切换层级时下方内容会整体上下跳**（见 §12-A4）。
- 列表行实际高度 ≈ 15sp 标题 + 2dp + 12sp 副标题 + 上下 padding 12dp×2 ≈ 60dp（minHeight 48dp 只是下限）。
- 书单网格：手机 2 列，屏幕宽 ≥600dp 时 3 列（`CategoryView.resolveColumns`，`CategoryView.java:213–222`）；列表/网格左右 20dp、底部 20dp、`clipToPadding=false`。
- 骨架：4 条 56dp 横条 + 8dp 间距，左边距 20dp（与真实列表行高度接近，但书单网格页的骨架仍是横条，和 2 列封面网格形态不一致，见 §12-A6）。

### 5.3 语言 chips（程序化创建，规格最特殊）

- 9 个，顺序固定：**全部语言 / 简体中文 / 繁体中文 / 英语 / 日语 / 俄语 / 法语 / 德语 / 西班牙语**（值：`"" / chinese / traditional chinese / english / japanese / russian / french / german / spanish`）。
- 每个 chip：`TextView`，14sp，居中，`minHeight=32dp`，水平 padding 14dp、垂直 padding 6dp，右侧间距 8dp；
- 背景 `@drawable/bg_segment_selector`（选中=浅绿底 10dp 圆角，未选中透明）；文字色 `@color/segment_text`（选中 primary，未选 ink_secondary）；
- 选中态通过 `setSelected(true)` 表达；contentDescription = 「语言名」+ 选中时「，已选中」；
- 位于 `HorizontalScrollView` 内横向滚动，无「更多语言」入口、无滚动提示。
- 与项目规范冲突点：`DESIGN_UI.md §4` 要求可点击控件 48dp 触控下限，chips 只有 32dp；且它和分段栏（36dp，等分宽度）长得类似但行为不同（自适应宽度、可横向滚动）。

---

## 6. 交互逻辑

### 6.1 层级导航矩阵

| 当前层级 | 操作 | 结果 |
|---|---|---|
| L0 一级目录 | 点任意分类行 | 进入 L1（本地数据，无网络） |
| L1 二级列表 | 点首行「全部」 | 进入 L2，书单=该一级分类自身（不限语言） |
| L1 | 点子分类行 | 进入 L2，书单=该子分类（不限语言） |
| L2 书单 | 点顶部返回「‹ 历史」 | 回 L1（重建 L1 行数据；同一 ListView 复用，滚动位置是否保留需真机验证） |
| L1 | 点顶部返回「‹ 全部分类」 | 回 L0（重建 L0 行数据；滚动位置是否保留需真机验证） |
| L0 | 点顶部返回 | 无返回按钮（隐藏） |
| L0 | 按系统返回键 | 退出分类，切回「热门」分段（会触发热门榜重新请求） |
| L1/L2 | 按系统返回键 | 等价于点顶部返回，逐层回退 |
| L2 | 点语言 chip | 清空当前书单 → 骨架 → 从第 1 页重新请求（新语言） |
| L2 | 滚动到接近底部 | 若还有下一页且未在加载，自动请求下一页（阈值：最后可见项 + 4 ≥ 总数） |
| L2 | 点书籍卡片 | 原生详情（hash 已就绪）或「映射 → 原生 / 网页详情」（见 §6.4） |

补充：L1→L0、L2→L1 是**重建行数据**（同一个 ListView 复用）而不是恢复滚动位置；三层之间没有可点击的面包屑，只有逐级返回。

### 6.2 语言筛选

- 只在 L2 出现；默认「全部语言」；切换分类（L1→L2）时语言强制回到「全部语言」（`openBooks()` 里 `language=""`）。
- 切换语言后：`page=0`、`pageUrlPattern=""`、清空 BookAdapter、`bookGrid.setSelection(0)`、显示骨架，再请求第 1 页（`selectLanguage()` → `loadBooks(1, true)`）。
- 加载过程中 chips 仍可点击；旧响应由 `requestToken` 丢弃，不会覆盖新结果。
- 语言与分类是 AND：请求 URL 形如 `/category/17/History/s/?languages%5B0%5D=chinese&page=2`。
- 简/繁分开，不合并；没有「中文 = 简体 OR 繁体」选项。

### 6.3 分页

- 每页 50 本；网站 `pagesTotal` 实测 3（一级分类「历史」、子分类「历史小说」、筛选后都一样是 3 页）。
- `hasMore = current < totalPages`；滚动触底条件：`firstVisibleItem + visibleItemCount + 4 >= totalItemCount`（阈值 4）。
- 下一页 URL 优先用**上一页 HTML 里 `pagerOptions.baseUrl`**（自带分类与语言参数，`%number%` 替换），而不是客户端自己拼；首屏 URL 由客户端按分类页 HTML 里 `searchForm.action` 构造。
- 加载更多提示：底部「正在加载更多…」；**没有「没有更多了」结束提示**（`no_more` 字符串存在但未使用）。
- 分页失败：保留已加载书单，仅 toast 错误文案，不回退到错误页。
- 旧响应保护：所有分类请求（树/书单/语言）共用一个递增 `requestToken`，回调 token 不一致直接丢弃。

### 6.4 书籍点击 → 详情

```
点击卡片
├─ Book.hash 非空（已是原生书籍）→ BookDetailActivity.open(...)
└─ Book.hash 为空（网页书目）
   ├─ mappingNative=true 时直接忽略后续点击（防重入，无提示）
   ├─ toast「正在匹配书籍详情…」
   ├─ 后台 BookMapper.mapToNative(book)（eapi 搜索 → ID 全等 → 详情 → terms_hash 校验）
   ├─ 成功 → BookDetailActivity.open(verifiedBook)
   └─ 失败 → AlertDialog：
        标题「网页详情」
        正文「该书籍暂时无法在应用内打开原生详情，可在浏览器查看网页详情。」
        按钮：取消 / 在浏览器打开
        （webUrl 为空 → toast「该书籍缺少可打开的详情地址」；无浏览器 → toast「没有可用的浏览器」）
```

- 映射入口没有 loading 态/进度条，只有 toast；映射期间点其他书会被 `mappingNative` 静默忽略。
- 映射结果不缓存：同一本书再次点击仍会重新走一次映射请求链。
- 「在浏览器打开」会离开 App（不做内置 WebView，网页详情页有 513 浏览器验证）。

### 6.5 分类与全局搜索的互切

书库顶部搜索框在分类模式下仍然显示、可用：

1. 在分类任意层级输入关键词搜索 → `searchMode=true`，分段栏隐藏，**分类视图隐藏**，内容区显示全库搜索结果的网格；
2. 搜索态清空/返回 → 恢复进入搜索前的 `rankMode`（=分类），分类视图重新显示，**内部层级/语言/页/滚动位置原样保留**（不重新请求）；
3. 因此搜索的本质是「离开分类找全库书」，不是「在分类内搜索」；UI 没有任何提示说明这一点。

### 6.6 状态保留策略

| 动作 | 分类视图状态 |
|---|---|
| 分类 ↔ 热门/猜你喜欢 分段切换 | 保留（视图隐藏但实例与数据都在） |
| 切到其它底部 tab 再回书库 | 保留 |
| 从分类打开书籍详情再返回 | 保留（详情是独立 Activity，CategoryView 未销毁） |
| Android 进程被杀后重启 | 丢失，回到 L0 骨架并重新请求分类目录 |
| 搜索再清空 | 保留（见 §6.5） |

---

## 7. 状态机

### 7.1 状态定义

| 状态 | 触发 | 显示 | 隐藏 | 可用操作 |
|---|---|---|---|---|
| `skeleton` 骨架 | 首次加载分类目录 / 首次进入书单 / 切语言 / 重试 | `ll_cat_skeleton`（4 条 56dp） | 列表、网格、空、失败、加载更多 | 语言 chips（L2，可点但会再次重置请求） |
| `list` 列表 | 分类目录加载成功 / 进入 L1 | `lv_cat_categories` | 其余全部 | 点行、顶部返回、系统返回 |
| `content` 网格 | 书单加载成功且有书 | `gv_cat_books`（+ 分页追加） | 其余全部 | 滚动加载、点书、切语言、返回 |
| `empty` 空结果 | 书单返回明确「无结果」或有效书为 0 | `ll_cat_empty` | 其余全部 | 有语言→「全部语言」按钮；无语言→「刷新」按钮 |
| `error` 失败 | 分类目录或书单请求失败 | `ll_cat_error` | 其余全部 | 「重试」（L2 重试当前书单第 1 页；L0/L1 重试分类目录） |
| `loadingMore` | 追加分页请求中 | 底部「正在加载更多…」 | — | 继续滚动（会等待当前请求） |

### 7.2 失败状态的文案细分（`CategoryView.categoryErrorMessage`，`CategoryView.java:736–754`）

| 异常类型 | 用户可见文案 | 是否可重试 |
|---|---|---|
| 网络失败（`ERROR_NETWORK`） | 网络连接失败，请检查网络后重试。 | 重试按钮 |
| 网页验证（`ERROR_CHALLENGE`） | 需要完成网页验证：网站要求先通过访问验证，请稍后重试。 | 重试按钮（不自动切镜像） |
| 分类不存在（`ERROR_NOT_FOUND`） | 这个分类在网站上不存在或已调整。 | 重试按钮 |
| 页面结构变化（`ERROR_PARSE`） | 页面结构变化：暂时无法解析分类页面，请稍后重试。 | 重试按钮 |
| 其它（非数据源异常） | `Ui.errorText(e)` 的中文网络提示（为空时用「分类加载失败，请稍后重试。」） | 重试按钮 |

- 错误态共用一个标题「分类加载失败」，图标 `ic_refresh`，按钮「重试」；
- 不向用户展示接口名、HTTP 状态码、解析器堆栈、镜像域名等技术细节；
- 分类加载失败**没有**书库那种「重试会自动切换服务器」的故障切换。

### 7.3 空状态（仅 L2）

| 场景 | 标题 | 说明 | 按钮 |
|---|---|---|---|
| 选了语言后无结果 | 当前条件没有结果 | 可以换个分类或语言试试；部分书籍尚未分类，可通过搜索查找。 | **全部语言**（点=清掉语言，重新请求） |
| 不限语言但该分类无书 | 当前条件没有结果 | 这个分类暂时没有可展示的书；部分书籍尚未分类，可通过搜索查找。 | **刷新**（点=重试第 1 页） |

---

## 8. 数据流（供 review 判断 UI 与数据是否匹配）

```
用户点「分类」分段
  → LibraryView.showCategoriesMode()：清空书库网格、隐藏书库各状态、显示 CategoryView
  → CategoryView.onShown()（仅首次）→ loadTree()
      → Task.run(WebCategorySource.fetchTree())
      → 成功：baseUrl + 39 个 Category（含 children）→ renderRoot() → list 状态
      → 失败：error 状态（分类目录加载失败）

点一级分类 → openChildren()（纯本地）→ list 状态
点「全部」/子分类 → openBooks() → loadBooks(1, true)
      → 骨架
      → Task.run(WebCategorySource.fetchBooks(baseUrl, category, language, page, pattern))
      → 成功：CategoryPage{ items, current, totalPages, pageUrlPattern }
           → 替换/追加 BookAdapter → updateHeader()（页码状态）→ content 或 empty
      → 失败：error（或分页失败时 toast + 保留 content）

点书 → openBook() → 有 hash：BookDetailActivity
              → 无 hash：BookMapper.mapToNative()（阻塞，后台线程）
                   → 原生详情 or 网页详情弹窗

切语言 → selectLanguage() → loadBooks(1, true)（旧请求 token 作废）
滚动触底 → loadBooks(page + 1, false)（token 自增，旧响应丢弃）
```

补充：分类数据来自网页分类目录（不是 `/eapi`）；`terms_hash` 命中才允许进原生详情，网页 `termshash`/短 token **不能**直接当 eapi hash。这些是数据层结论，UI 层只消费 `Book.hash` 是否就绪与 `BookMapper` 的成败。

---

## 9. 数据模型对 UI 的影响

| 字段 | 用途 | UI 表现 |
|---|---|---|
| `Category.sourceCategoryId` + `parentId` | 分类唯一标识 | 不直接展示；用于取数、避免同名分类串层 |
| `Category.sourceName` | 网站英文原名 | 有中文映射时作为副标题（如 History） |
| `Category.displayName` | 中文展示名 | 行标题；无映射时等于 sourceName（显示英文） |
| `Category.sourceUrl` | 网站分类链接 | 仅取数，不展示 |
| `Category.bookCount` | 子分类书目数（网站全量计数） | 右侧「N 本」；**不随语言筛选变化** |
| `Category.type` | fiction / non-fiction | 无中文映射时作为副标题「虚构类/非虚构类」 |
| `CategoryPage.current/totalPages` | 网页分页 | 副标题「第 p/total 页 · 已加载 n 本」 |
| `Book`（网页字段） | 书名/作者/封面/语言/年份/格式/大小 | 复用书库卡片；点击时再决定详情入口 |

中文映射覆盖率：39 个一级分类 + Fiction/History/Biography 的二级分类有中文名；其余二级分类保留英文（目录可能中英混排）。

---

## 10. 无障碍与文本规范

- 返回按钮：`contentDescription=@string/category_back_desc`（「返回上一级分类」），48dp 高，主色文字；
- 语言 chips：`contentDescription` = 语言名 + 选中时「，已选中」；
- 列表行：没有单独 `contentDescription`，依赖 TalkBack 读取行内标题/副标题/数量；箭头 `contentDescription=null`（装饰性）；
- 书单卡片：复用 `BookAdapter`（封面 `contentDescription=@string/cover_desc`，书名/作者为文本）；
- 长文本：页面标题 `maxLines=1`、分类副标题 `maxLines=2`、列表行副标题 `maxLines=1`，均 ellipsize=end；
- 所有分类界面文案走 `@string/*`（语言 chips 展示名来自 `CategoryNames` Java 映射，非 string 资源，因为属于数据映射）；
- 触控：返回按钮 48dp、行实际 ≈60dp、按钮 style minHeight 48dp；**语言 chips 32dp 不满足 48dp 规范**。

---

## 11. 与项目设计规范的一致性检查

| 检查项 | 结果 |
|---|---|
| 颜色是否全部走 `@color/*` | ✅ |
| 间距/圆角是否走 `@dimen/*` | ✅ 布局；chips 为程序化 dp（计算得出，数值 14/6/32/8） |
| 字体是否复用 `TextAppearance.ZL.*` | ✅ 页面标题 Section、正文 Body、说明 Caption |
| 按钮是否复用样式 | ✅ GhostButton |
| 可点击控件 ≥48dp | ❌ 语言 chips 高 32dp（分段栏本身是 36dp，属既有模式） |
| 文案是否走 `@string/*` | ✅（分类中文映射除外） |
| 是否不用 ConstraintLayout / 不新增第三方依赖 | ✅ LinearLayout / FrameLayout / ListView / GridView |
| 是否展示技术要求信息 | ⚠️ 常驻「网页分类目录」来源标签偏技术（见 §12-A12）；错误文案已做用户化 |
| 状态覆盖 | ✅ 骨架/列表/网格/空/失败（失败细分 5 种）+ 加载更多 |
| 分页结束提示 | ❌ 无「没有更多了」 |

---

## 12. 候选问题清单（静态分析得出，未真机验证；供 review 判断）

> 说明：以下分为 A（较可能影响体验/与规范不一致，建议优先看）、B（需产品判断或真机确认）、C（开放产品决策）。每条附代码/布局依据。

### A 级：较可能影响体验 / 与项目规范不一致

| # | 问题 | 依据 | 影响 / 建议方向 |
|---|---|---|---|
| A1 | **顶部信息层级重复**：书库 26sp 标题 + 搜索框 + 分段栏 + 分类页自己的面包屑/16sp 标题/2 行副标题/语言栏；「分类」两处出现（分段选中项 + L0 标题） | `view_library.xml:12,24,94,130`；`view_categories.xml:10–91`；`CategoryView.java:599–601` | 小屏内容区被压缩；视觉主次不清。可考虑分类模式隐藏/精简书库标题与搜索框，或让分类成为独立页 |
| A2 | **面包屑不可导航**：只有逐级返回；L2→L0 要点两次返回；不能点「历史」跨级；无「全部分类」直达 | `CategoryView.java:238–252,585–596` | 深层返回成本高；建议可点击面包屑或「返回一级目录」快捷入口 |
| A3 | **L0 按返回会切回热门并重新请求**：`switchRank(0)` → `loadPage(1,true)` 清空热门列表并显示骨架，不是恢复之前的榜单 | `LibraryView.java:313–327,465–485,531–565` | 用户可能意外看到加载；建议返回 L0 时保留热门快照或仅切分段不重载 |
| A4 | **头部高度跳变**：L0 无返回按钮（行高≈16dp）→ L1/L2 有 48dp 返回按钮，内容整体下移约 32dp | `view_categories.xml:19–34,50–72`；`CategoryView.java:589–597` | 层级切换时页面跳动；建议给面包屑行固定高度或用占位保持稳定 |
| A5 | **语言 chips 触控 32dp**，低于 `DESIGN_UI.md` 的 48dp 触控下限，也小于分段栏 36dp；外观与分段栏相似但行为不同（自适应宽、横向滚动、无 ripple） | `CategoryView.java:632–665`；`DESIGN_UI.md:177` | 小屏/大手指误触；建议 40–48dp 高或改用横向 ScrollView + chip 规范 |
| A6 | **骨架形态与内容形态不匹配**：L2 是 2/3 列封面网格，骨架却是 4 条横条；L1 行约 60dp 而骨架条 56dp | `view_categories.xml:131–164`；`row_category.xml`；`row_book_card.xml` | 加载态与真实内容差异大；建议 L2 用 2 列封面骨架 |
| A7 | **分页没有结束提示**：3 页上限时用户无法判断是否已加载完（存在未使用的 `no_more` 字符串） | `strings.xml:73`；`CategoryView.loadBooks` | 建议加载完显示「没有更多了」或说明网站分类仅展示前 3 页 |
| A8 | **详情映射等待无 loading/进度**：仅 toast；映射期间点其它书被静默忽略；失败才弹窗 | `CategoryView.java:500–542` | 弱网下体验像「点了没反应」；建议卡片上 loading 态、可取消、串行提示 |
| A9 | **映射结果不缓存**：同一本无 hash 的书每次点击都重跑「eapi 搜索 + 详情」两次请求 | `CategoryView.java:504–521`；`BookMapper` | 重复网络开销/变慢；建议内存缓存已验证的 id→hash |
| A10 | **切语言清空书单→骨架**：无「保留旧内容 + 顶部/局部 loading」，滚动位置丢失 | `CategoryView.java:393–407,418–427` | 切换筛选时闪烁明显；建议保留旧列表，加载完成后整体替换 |
| A11 | **分类失败没有服务器切换**：书库失败重试会自动换镜像，分类失败只重试同域名；challenge 态无自助操作 | `CategoryView.java:196–207` vs `LibraryView.retryWithFailover` | 与书库体验不一致；建议复用故障切换或给出镜像提示 |
| A12 | **常驻「网页分类目录」来源标签**：偏技术、无操作、占右上角；L0 时 back 隐藏后该行只剩这个标签，视觉不稳 | `view_categories.xml:41–47`；`string category_source_label` | 建议改为不常驻（放到说明/关于）或更产品化的来源标注 |

### B 级：需产品判断 / 真机确认

| # | 问题 | 说明 |
|---|---|---|
| B1 | 39 个一级分类平铺、无搜索/分组/排序（网站顺序≈字母序），中文用户查找成本高 | 需要产品决定是否加分类搜索/折叠/最近使用 |
| B2 | 二级分类最多 49 行（Medicine）+「全部」行，无分组/折叠；「全部」行与子分类视觉相似，易误点 | 可考虑「全部」行加视觉区分或分组标题 |
| B3 | 中文映射不完整，未映射分类显示英文，目录中英混排 | 可接受则保持，否则补映射 |
| B4 | 语言只有 9 种（网站 175 种），无「更多语言」；简繁分开、不提供「中文（简+繁）」 | 需产品决定筛选范围与合并语义 |
| B5 | 分类状态只在进程内保留，冷启动回到 L0 | 书库本身也不持久化栏目选择，需确认是否可接受 |
| B6 | L2 点「全部」时标题只有一级分类名，与 L1 标题相同，仅副标题/返回按钮不同 | 易迷失；建议标题加「全部」或副标题强化 |
| B7 | 分类页顶部搜索执行的是全库搜索（离开分类），UI 没有说明 | 需明确文案或改为分类内搜索 |
| B8 | 语言 chips 若程序化重建，横向 ScrollView 是否自动露出当前选中项未真机验证 | 建议改为预创建 9 个 chip 只切 selected，避免重建/滚动跳动 |
| B9 | 大字体（fontScale>1）、横屏、≥600dp 3 列布局未真机验证 | 固定 16sp/12sp + maxLines 在极端缩放下的表现未知 |
| B10 | 分类无下拉刷新；网站分类可能变化，用户只能返回/重进 | 与项目整体一致，但分类更依赖网页，建议考虑刷新入口 |
| B11 | 空态按钮文案随语言状态在「刷新 / 全部语言」之间切换，语义变化 | 需确认用户能理解 |
| B12 | 用户无法预知哪本书会弹「网页详情」而不是原生详情 | 可在卡片上加标识或统一文案 |
| B13 | 站点每类最多 3 页（约 150 本）未在界面说明 | 建议在分类页脚注说明，避免「分类只有 150 本」的误解 |
| B14 | 加载更多时继续滚动可能重复触发？代码有 loading 防重入，但加载期间无 skeleton，仅底部文字 | 可接受，可考虑底部 spinner |

### C 级：开放产品决策（review 建议讨论）

1. 分类是否应作为**独立页面/底部 tab**，而不是书库内容区的一个分段？（当前方案把 26sp 书库标题、搜索框、分段栏都留在分类页上方，是 A1/A3 的根源）
2. 语言筛选放在哪一层最合适？分类页顶部常驻 chips / 底部筛选面板 / 多选 + 确认？（当前是顶部单行横向 chips，9 个）
3. 39 + 802 的分类树用什么形态？长列表 / 一级+二级折叠 / 可搜索的全屏分类页 / 最近使用？
4. 简/繁中文是否提供「中文（简+繁）」合并查询（网站支持多值 AND/OR 语义）？
5. 未分类书籍的提示（「部分书籍尚未分类，可通过搜索查找」）是否需要在分类页更显眼的位置？
6. 分类浏览只有 3 页，是否需要在 UI 上说明，或提供「按分类 + 关键词搜索」的扩展入口？

---

## 13. 给 review AI 的问题清单（可直接复制）

请重点判断：

1. **信息架构**：分类放在书库分段里是否合理？分类页顶部保留「书库」标题 + 搜索框 + 分段栏是否造成层级重复（A1）？是否应改为独立页面？
2. **导航模型**：三层「目录 → 二级 → 书单」+ 逐级返回是否清晰？面包屑是否应可点击（A2）？L0 返回切到热门并重载是否合理（A3）？
3. **状态表达**：骨架/列表/网格/空/失败（5 类失败）是否覆盖完整？空态两种按钮语义（刷新 / 全部语言）是否清晰？错误态是否应与书库一致提供换服务器重试（A11）？
4. **语言筛选**：默认「全部语言」+ AND 条件 + 切换重置分页是否明显？chips 的位置、数量（9 个）、单行横向滚动、32dp 触控高度是否可接受（A5）？需要「中文（简+繁）」合并吗（C4）？
5. **分页**：每类 3 页上限、滚动加载、无结束提示（A7）、无页码跳转，这样的策略是否会让用户误以为只有 150 本书？
6. **详情入口**：无原生映射的书点击后只 toast + 弹窗到浏览器（A8/A9/B12），是否应加卡片标识、加载态或统一入口？
7. **视觉一致性**：分类页标题 16sp（Section）与书库 26sp（PageTitle）的层级关系、L0/L1/L2 头部高度跳变（A4）、骨架形状（A6）、chips 与分段栏的相似外观（A5）是否需要统一？
8. **可访问性/适配**：32dp chips、列表行无 contentDescription、大字体/横屏/3 列未验证（B8/B9），有哪些必须修复？
9. **内容完整性**：中文映射不完整、语言只 9 种、备注文案是否满足「普通用户看不到技术信息」的要求（A12）？
10. 若只能改 3 处，应优先改哪三处？请给出具体布局/交互改法。

---

## 14. 代码索引（供有仓库权限的 review 使用）

| 文件 | 关键位置 | 内容 |
|---|---|---|
| `app/src/main/res/layout/view_categories.xml` | 1–271 | 分类页完整视图树（面包屑/标题/副标题/语言栏/内容区/骨架/空/错/加载更多） |
| `app/src/main/res/layout/row_category.xml` | 1–53 | 分类列表行 |
| `app/src/main/java/com/zlib/clone/ui/CategoryView.java` | 50–60 | 层级常量、列数、分页阈值、语言取值 |
| 同上 | 113–211 | init：绑定视图、ListView/GridView 监听、空/错按钮、构造 chips |
| 同上 | 213–222 | 2/3 列判定（≥600dp 三列） |
| 同上 | 228–235 | `onShown()` 首次加载 |
| 同上 | 238–252 | `handleBack()` 层级返回 |
| 同上 | 258–301 | 分类目录加载 |
| 同上 | 303–374 | L0 渲染 / L1 构建 / 行点击 |
| 同上 | 380–407 | 进入书单 / 切换语言（重置分页） |
| 同上 | 409–494 | 书单加载、追加分页、旧响应丢弃、空/错分流 |
| 同上 | 500–583 | 书籍点击 → 原生详情 / 映射 / 网页详情弹窗 |
| 同上 | 585–630 | 头部标题/副标题/返回按钮/语言栏显隐 |
| 同上 | 632–665 | 语言 chips 构建与选中态 |
| 同上 | 671–754 | 五类状态展示与错误文案映射 |
| 同上 | 779–844 | 行模型与 ListView Adapter |
| `app/src/main/java/com/zlib/clone/ui/LibraryView.java` | 148–155 | 创建并挂载 CategoryView |
| 同上 | 211–214 | 分类分段点击入口 |
| 同上 | 313–327 | 系统返回键与分类层级回退 |
| 同上 | 465–503 | 分段切换 / 显示分类视图 |
| 同上 | 531–544 | 搜索与分类视图互切 |
| `app/src/main/res/values/strings.xml` | 34–65 | 分类相关文案（标题/空/错误/网页详情） |
| `app/src/main/res/values/colors.xml` | 4–16 | 色板 |
| `app/src/main/res/values/dimens.xml` | 3–20 | 间距/触控/圆角 |
| `app/src/main/res/values/styles.xml` | 3–57 | 字号样式/按钮/分段栏 |
| `app/src/main/java/com/zlib/clone/ui/BookAdapter.java` + `row_book_card.xml` | — | 书单网格复用的书库卡片 |

---

## 15. 未验证事项（重要）

本文档无法提供真机截图或运行验证，以下均未验证：

1. 实际渲染效果（字号、行高、字重、字体 fallback、chips 换行/滚动、深色模式无、系统字体缩放）；
2. 层级切换时的视觉跳动（A4 是从布局参数推导的估算，需真机确认）；
3. 语言 chips 重建后的横向滚动位置/选中项可见性（B8）；
4. 手势返回、TalkBack 实际朗读顺序、48dp 触控热区实测；
5. 横屏、≥600dp 三列网格、大字体（fontScale）布局；
6. 弱网/慢网下骨架、toast、映射等待的真实观感；
7. 分类状态在 Activity 重建（旋转）后的保留行为（当前 View 由 MainActivity 常驻，旋转重建时按 `onSaveInstanceState` 只保留 tab，分类层级不保留——这一点也没有真机验证）。

如 review 需要真机证据，需要先在 Android 设备上安装 `ZLibClone-1.2.0.apk` 后补充截图/录屏。
