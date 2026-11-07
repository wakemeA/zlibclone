# 分类（Category）模块 UI/UX 改造 — 变更说明

- 基线：ZLibClone v1.2.0（versionCode 7，`app/src/main`，纯 Java / Android Framework，无 Gradle、无三方 UI 依赖）
- 交付版本：**v1.2.1（versionCode 8）**；`tools/build.sh` 已升版，产物 `ZLibClone-1.2.1.apk`（155,690 B，SHA-256 `5a410b51…72c9a51`）
- 完整补丁：`分类UI改造-v1.2.0.diff`（`diff -ruN`，15 个文件 = 13 个 app 源码/资源 + `tools/build.sh` 升版 + `README.md` 构建记录；已用 `patch -p1 --dry-run` 验证 15/15 可零冲突合入 v1.2.0 基线）
- 构建验证：aapt2 compile + link、javac、d8、zipalign(4)、apksigner（v1+v2+v3）全部通过；`aapt dump badging` 确认 versionCode 8 / versionName 1.2.1；产物 `build/ZLibClone.apk` → 交付 `../ZLibClone-1.2.1.apk`（155,690 B，SHA-256 `5a410b51…72c9a51`）

---

## 一、改动文件清单（13 个）

| # | 文件 | 类型 | 核心改动 |
|---|---|---|---|
| 1 | `app/src/main/java/com/zlib/clone/ui/CategoryView.java` | 修改 | 顶栏层级联动、48dp 双段面包屑、9 个预创建语言 Chip、双形态骨架、切语言过渡、分页尾注、L0/L1 滚动记忆、在途请求失效、映射缓存+可取消弹窗、错误重试接镜像切换 |
| 2 | `app/src/main/java/com/zlib/clone/ui/LibraryView.java` | 修改 | 新增 `setChromeCompact(boolean)`；分类 L1/L2 隐藏书库标题+全库搜索框；分类→热门复用缓存不重刷；恢复榜单滚动位置；分类视图缓存不再被清空 |
| 3 | `app/src/main/java/com/zlib/clone/net/BookMapper.java` | 修改 | 新增 `cacheKey(Book)`：映射缓存键（优先 `id:`，回退 `url:`）；保持纯 Java（JVM 测试可编译） |
| 4 | `app/src/main/res/layout/view_categories.xml` | 重写 | 删除 `tv_cat_source`；固定 48dp 双段面包屑（root/parent）；骨架拆 `ll_cat_skeleton_list` / `ll_cat_skeleton_grid`；语言条 48dp；标题/副标题/尾注视图对齐 |
| 5 | `app/src/main/res/layout/row_language_chip.xml` | 新增 | 语言 Chip：外层 48dp 触控热区 + 内层 32dp 视觉胶囊（圆角 16dp、paddingH 14dp） |
| 6 | `app/src/main/res/values/strings.xml` | 修改 | 新增/调整分类文案；删除 `category_source_label`、`category_mapping_loading`、`category_back_desc` |
| 7 | `app/src/main/res/values/dimens.xml` | 修改 | 新增 `radius_pill`、`breadcrumb_height`、`chip_touch_height`、`chip_height`、`chip_padding_h`、`skeleton_row_height`、`skeleton_card_height` |
| 8 | `app/src/main/res/values/styles.xml` | 修改 | 新增 `TextAppearance.ZL.Breadcrumb`（14sp/主色）、`TextAppearance.ZL.Chip`（14sp/状态色） |
| 9 | `app/src/main/res/drawable/bg_language_chip.xml` | 新增 | Chip 背景 selector（selected → 选中形态） |
| 10 | `app/src/main/res/drawable/bg_language_chip_selected.xml` | 新增 | 选中：`@color/selected_bg` 底 + 1dp `@color/primary` 描边 + 16dp 圆角 |
| 11 | `app/src/main/res/drawable/bg_language_chip_normal.xml` | 新增 | 未选中：`@color/paper` 底 + 1dp `@color/divider` 描边 + 16dp 圆角 |
| 12 | `app/src/main/res/color/language_chip_text.xml` | 新增 | Chip 文字颜色 selector：选中 `@color/primary` / 未选中 `@color/ink_secondary` |
| 13 | `app/src/main/res/drawable/bg_skeleton_card.xml` | 新增 | 书单网格骨架卡片：`@color/divider` 底 + `@dimen/radius_card`(16dp) 圆角 |

---

## 二、任务逐项落实

### 任务 1：精简顶部层级与消除高度跳变

| 要求 | 实现位置 |
|---|---|
| 删除右上角 `tv_cat_source`（"网页分类目录"） | `view_categories.xml`（已无该 id）；`strings.xml` 删除 `category_source_label` |
| `setChromeCompact(boolean)`：L0=false（26sp 标题+搜索框），L1/L2=true（仅留 36dp 分段栏），离开分类恢复 false | `LibraryView.java:596`（实现）、`CategoryView.java:74`（`ChromeHost` 接口）、`CategoryView.java:819 updateHeader()→syncChrome()`（层级变化即回调）；离开分类在 `switchRank()` 中 `setChromeCompact(false)` |
| L0 隐藏面包屑与标题、只显示副标题（marginTop=8dp，文案「共 N 个一级分类 · 部分未分类书籍可通过顶部搜索查找」） | `CategoryView.updateHeader()`（L0 时 `Ui.display(breadcrumbBar/titleView,false)`）；`view_categories.xml` 副标题 `@dimen/space_8`；`strings.xml category_root_subtitle`；分类树加载完成前隐藏副标题，避免出现「共 0 个」 |
| 面包屑固定 48dp、center_vertical、仅 L1/L2 显示 | `view_categories.xml`（`layout_height=@dimen/breadcrumb_height`、`gravity=center_vertical`、默认 gone）；`updateHeader()` 控制显隐 |
| `btn_cat_back_root`：minHeight 48dp、paddingH 8dp、主色、固定「‹ 全部分类」、点击 `renderRoot()` 回 L0 | `view_categories.xml` + `CategoryView.java:227`；颜色走 `TextAppearance.ZL.Breadcrumb` |
| `btn_cat_back_parent`：仅 L2、文案「/ {一级分类名}」、点击回对应 L1 | `CategoryView.updateHeader()` 设置文案/描述；点击 `openChildren(parent, true)`（带 L1 滚动恢复），默认 gone |
| L1 首行「浏览全部 · {一级分类中文名}」+ 副标题「查看该分类下全部汇总书单」 | `CategoryView.java:413-414`（`category_browse_all` / `category_browse_all_subtitle`） |
| 从首行进 L2 标题显示「{一级分类中文名} · 全部」 | `CategoryView.updateHeader()` 中 `category == parent` 分支（`category_all_title`） |

### 任务 2：语言筛选 Chips 合规化与视觉区分

| 要求 | 实现位置 |
|---|---|
| `hsv_cat_languages` minHeight=48dp、gravity=center_vertical | `view_categories.xml` |
| 每个 Chip 48dp 触控热区 + 32dp 视觉胶囊、gravity=center、paddingH 14dp | `row_language_chip.xml`（外层 `@dimen/chip_touch_height` / 内层 `@dimen/chip_height` + `@dimen/chip_padding_h`） |
| 不复用分段栏背景；GradientDrawable 胶囊：选中 `selected_bg`+1dp `primary` 边框+主色加粗；未选中 `paper`+1dp `divider` 边框+`ink_secondary` 正常字重 | `bg_language_chip*.xml`、`color/language_chip_text.xml`；字重在 `updateLanguageChips()` 用 `Typeface.DEFAULT_BOLD/NORMAL` |
| init 一次性创建 9 个 Chip，切语言只遍历更新 `setSelected`/字重，不 `removeAllViews()` | `CategoryView.java:267 createLanguageChips()`（init 内）→ `updateLanguageChips()`（`openBooks`/`selectLanguage` 只更新状态） |
| 选中后计算 Chip 中心 X，`smoothScrollTo` 居中 | `CategoryView.scrollSelectedLanguageToCenter()`（`selectLanguage()` 中调用） |

### 任务 3：骨架形态匹配、切语言平滑过渡与分页尾注

| 要求 | 实现位置 |
|---|---|
| `ll_cat_skeleton_list`：4 条 60dp 横条（L0） | `view_categories.xml` + `@dimen/skeleton_row_height`；`showSkeletonStateForLevel()` 按层级切换 |
| `ll_cat_skeleton_grid`：2 行×2 列、每块约 220dp、圆角 16dp、间距 12dp、背景 `@color/divider`（L2 首次进入） | `view_categories.xml` + `@dimen/skeleton_card_height` + `bg_skeleton_card.xml` |
| L2 切语言：保留旧内容、`alpha=0.4f`、禁用滚动/点击、显示「正在切换语言筛选…」；返回后恢复 alpha、替换数据、`setSelection(0)`；空/错再切 empty/error | `selectLanguage()` → `loadBooks(1,true,true)`；`startContentTransition()/endContentTransition()`；`loadBooks` 成功分支 `endContentTransition + setItems + setSelection(0)`；成功为空→`showEmptyState()`，失败→`showErrorState()` |
| `!hasMore` 且列表非空：`tv_cat_loading_more` 显示「已展示该分类前 N 本精选藏书 · 更多书籍请使用顶部搜索」（ink_secondary） | `refreshPaginationHint()`；文案 `category_end_hint`（N 取实际已加载本数，3 页满载时即 150） |

### 任务 4：导航滚动位置记忆、详情映射反馈与缓存

| 要求 | 实现位置 |
|---|---|
| `l0ScrollIndex/l0ScrollTop`、`l1ScrollIndex/l1ScrollTop`；L0→L1 前记录；L1→L0 在 `notifyDataSetChanged()` 后 `setSelectionFromTop`；L1↔L2 同理 | `captureCategoryScroll()` / `restoreCategoryScroll()`（`onRowClicked`、`handleBack`、面包屑点击）；`renderRoot(true)`、`openChildren(parent,true)` |
| 分类→热门：热门 Adapter 已有缓存则不 `loadPage(1,true)`，只切显隐+恢复列表/滚动 | `LibraryView.switchRank()`（`cachedRankMode/rankLoaded/adapter.count` 判断）；`showCategoriesMode()` 不再 `adapter.clear()`；`rememberRankScroll()/restoreRankScroll()` |
| 静态 `LruCache<String,Book>(64)`（Key=id 或 webUrl）；映射成功写缓存；命中直接打开详情、跳过网络 | `CategoryView.java:71 MAPPING_CACHE`；`openBook()` 先查缓存；成功回调 `MAPPING_CACHE.put(BookMapper.cacheKey(book), result.book)` |
| 取消映射：废弃静默 Toast/忽略点击，改为 AlertDialog「正在匹配应用内书籍详情…」+「取消」；取消递增 token 丢弃回调 | `showMappingDialog()/cancelMapping()/dismissMappingDialog()`；`mappingToken` 校验回调；`onDetachedFromWindow()` 兜底关闭弹窗 |
| L0/L2「重试」对齐 `retryWithFailover`（有备用镜像先切换再请求） | `CategoryView.retryWithFailover()`：`Hosts.pickNext(Api.get().base())` → `Api.setBase` + `baseUrl` 对齐新镜像 → 再请求；分类不存在（`ERROR_NOT_FOUND`）不切镜像 |

---

## 三、新增/变更资源 ID 对照（Java ↔ XML 完全对齐）

- **id**：`btn_cat_back_root`、`btn_cat_back_parent`、`ll_cat_skeleton_list`、`ll_cat_skeleton_grid`、`tv_language_chip`（新布局）；`btn_cat_back`、`tv_cat_source` 已删除，Java 无残留引用
- **string（新增/调整）**：`category_root_subtitle`（新文案）、`category_back_parent`（`/ %1$s`）、`category_back_root_desc`、`category_back_parent_desc`、`category_browse_all`、`category_browse_all_subtitle`、`category_all_title`、`category_switching_language`、`category_end_hint`、`category_mapping_dialog_message`、`category_type_fiction`、`category_type_non_fiction`；删除 `category_source_label`、`category_mapping_loading`、`category_back_desc`
- **dimen（新增）**：`radius_pill`、`breadcrumb_height`、`chip_touch_height`、`chip_height`、`chip_padding_h`、`skeleton_row_height`、`skeleton_card_height`
- **style（新增）**：`TextAppearance.ZL.Breadcrumb`、`TextAppearance.ZL.Chip`
- **drawable（新增）**：`bg_language_chip`、`bg_language_chip_selected`、`bg_language_chip_normal`、`bg_skeleton_card`；**color（新增）**：`language_chip_text`
- **layout（新增）**：`row_language_chip`

---

## 四、产品硬约束保持情况

1. 分类 + 语言筛选仍为 **AND**：请求仍走 `WebCategorySource.fetchBooks(baseUrl, category, language, page, pattern)`，未改数据链路。
2. 切换分类/语言仍重置 `page=1`：`openBooks()` / `selectLanguage()` 均先清零 `page/totalPages/pageUrlPattern` 再 `loadBooks(1, ...)`。
3. 简繁独立：`Categories.LANGUAGE_VALUES` 未改动（`chinese` 与 `traditional chinese` 两个独立值，未合并）。
4. 5 类错误文案与 2 类空态保留：`category_error_network/challenge/not_found/parse/unknown`；空态按钮仍按「有语言→全部语言 / 无语言→刷新」切换。
5. 技术信息不外露：分类页非数据源异常只归入「网络失败 / 其它」两类用户文案，不展示 HTTP 状态码/英文异常/堆栈；分类页文案全部走 `strings.xml`。
6. 颜色引用 `@color/*`，间距/圆角/字号引用 `@dimen/*` 与 `@style/TextAppearance.ZL.*`；无 ConstraintLayout/RecyclerView/三方依赖。

---

## 五、验证记录

| 验证项 | 结果 |
|---|---|
| XML 结构（全部 `res/**/*.xml` 解析） | 通过，0 错误 |
| XML 资源引用（@string/@dimen/@color/@drawable/@style/@id/@layout） | 0 悬空引用 |
| Java `R.*` 引用 vs 资源定义 | 0 悬空引用 |
| aapt2 compile + link（资源 ID 生成、属性合法性） | 通过 |
| javac（source 8 / target 8，android-33 android.jar） | 通过（仅 deprecated API 提示） |
| d8 dex → zip → zipalign(4) → apksigner(v1+v2+v3) | 通过；产物 `build/ZLibClone.apk` 155,690 B |
| 版本号（`aapt dump badging`） | `versionCode=8` / `versionName=1.2.1` |
| SHA-256 / zipalign -c 4 | `5a410b518c59797ee0f714eb34c80de36233b6b1f2ac09459a3f5a99872c9a51` / 通过 |
| 补丁可合入性（对基线 `patch -p1 --dry-run`） | 13 个文件全部成功，0 冲突 |
| 项目自带 JVM 联网测试源码编译（BookMapper 仍不依赖 android.*） | 通过（`tools/webtest` 同一套 javac 命令） |
| 独立评审（另一 AI 评审员按规范逐条核对 + 状态机走查） | 发现并已修复 1 个高危、2 个低危问题（见第七节） |

**未做**：真机/模拟器运行验证（本环境无 Android 运行时），因此分类页手势、滚动恢复手感、弹窗与骨架屏的实际表现仍需真机确认。

---

## 六、实现决策说明

1. **映射缓存放在 `CategoryView`（UI 层）而非 `BookMapper`**：需求允许「CategoryView 或 BookMapper」。放在 UI 层可使用 `android.util.LruCache` 且不污染 `BookMapper`；`BookMapper` 只提供 `cacheKey()` 语义，保持纯 Java，项目自带 JVM 联网测试仍可直接编译运行。
2. **分页尾注使用实际本数**：文案为「已展示该分类前 %1$d 本…」，3 页满载时自然显示 150；若某分类不足 3 页则显示真实本数，避免写死 150 误导。
3. **切语言过渡的失败兜底**：请求失败时先恢复 `alpha=1`/可点击再切 error，避免书单永久半透明或被拦截触摸。
4. **镜像重试同步 `baseUrl`**：分类书单 URL 基于首次拉树得到的 `baseUrl`，切换镜像后同步该字段，保证 L2 重试真正走新镜像。
5. **升版交付**：按项目惯例升为 v1.2.1 / versionCode 8（`tools/build.sh` 已更新），产物 `ZLibClone-1.2.1.apk`；补丁同时包含 UI 改造、`tools/build.sh` 版本号与 README 构建记录。

---

## 七、独立评审后修正（2026-11-07）

评审用另一份只读视角按任务 1~4 + 硬约束逐条核对，并重点走查分类/搜索/榜单状态机。确认通过的项不在此重复，以下是评审发现并已修正的问题：

1. **[高] 内部返回时在途分类请求未失效**：`renderRoot()` / `openChildren()` 原先不递增 `requestToken`，L2 书单请求（网页 + PoW，通常数秒）在途时点面包屑返回 L1/L0，旧回调仍会执行 `showContentState()`，把一级/二级分类列表替换成书单网格/空态/错误态。
   - 修复：新增 `invalidatePendingRequests()`（`requestToken++`、`loading=false`、`endContentTransition()`、隐藏尾注），在 `renderRoot()` / `openChildren()` 入口调用；`loadBooks` 的 ok/err 回调再加 `level == LEVEL_BOOKS` 守卫做纵深防御。旧版同样存在该缺陷，本次一并修掉。
2. **[低] 语言 Chip 自动居中未扣横向 padding**：`hsv_cat_languages` 左右各有 20dp 内边距，原计算直接拿 `languageBar.getWidth()/2` 偏移，整体偏左约 20dp 且末尾 Chip 无法真正居中。
   - 修复：居中目标改为 `paddingLeft + chipCenter - hsvWidth/2`，最大滚动量按「左右 padding + 内容宽」计算。
3. **[低] 分类类型展示名硬编码在 Java**：L1 行副标题的「虚构类 / 非虚构类」原为 Java 字面量。
   - 修复：迁移到 `strings.xml` 的 `category_type_fiction` / `category_type_non_fiction`。

评审同时确认：搜索/清空搜索与分类视图切换无 token/数据错乱；返回键逐层回退正确；LibraryView 缓存恢复不会出现「猜你喜欢显示热门数据」；切语言过渡在成功/失败/返回路径都会恢复 `alpha=1` 与可点击状态。

**仍需真机确认**：分类页手势与滚动恢复手感、骨架/尾注/弹窗实际视觉、弱网下 PoW 解算与镜像切换耗时（本环境无 Android 运行时，只能保证编译与静态逻辑）。
