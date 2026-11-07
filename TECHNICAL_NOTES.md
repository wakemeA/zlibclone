# 技术说明与历史验证记录

> 本文保留项目最初的开发说明、接口研究和历史版本记录，内容可能包含已被后续版本修正的结论。
> 最新的项目介绍、下载和使用方式请见 [README](<README.md>)。
> 文中 `../research/`、`../apkwork/` 等路径指原开发环境的外部研究资料，未包含在本仓库中。

## ZLibClone —— Z-Library 客户端复刻（纯 Java / 零第三方依赖）

> 依据对官方 APK `com.positron_it.zlib v1.11.4` 的逆向结果（见上级目录 `../Z-Lib_1.11.4_复现分析.md`
> 与 `../apkwork/reports/*.md`）从零重写的 Android 客户端。
> **不包含任何书籍内容、不内置账号、不复用原作者签名与凭据。**

## 功能（v1.0）

| 模块 | 能力 |
|---|---|
| 启动 | 自动探活服务器（内置 16 个域名 → 也可手填），写入本地 |
| 登录 | 邮箱 + 密码 → `/eapi/user/login`；token 存 SharedPreferences |
| 书库 | 热门 / 分类 / 猜你喜欢 三个栏目；分类 = 网页分类目录（一级/二级 + 语言筛选 + 分页），分页加载 |
| 搜索 | 关键词搜索（`POST eapi/book/search`），分页加载 |
| 详情 | 书籍信息、封面、收藏/取消收藏、格式列表、下载、在线阅读 |
| 下载 | `GET /eapi/book/{id}/{hash}/file` 换直链 → 系统 DownloadManager 落盘到 `Download/`（带鉴权头） |
| 阅读 | WebView 打开 `readOnlineUrl`（注入登录 Cookie） |
| 我的 | 服务端 Saved / Downloaded 两个列表，支持取消保存/删除记录 |
| 个人 | 账号信息、下载额度、服务器地址、语言、登出 |

## UI 改版 v1.1（暖纸白 + 墨绿）

四个页面（书库 / 我的书籍 / 阅读 / 我的）按统一设计系统重做：

- **设计 token 集中管理**：`res/values/colors.xml`（paper #FAF8F3 / card #FFFFFF / ink #222822 / ink_secondary #5F685F / primary #2F5D4D / selected_bg #E8F0EA / divider #E6E2D8 / danger #B3564A）、`dimens.xml`（8pt 间距体系、16dp 卡片圆角、20dp 页边距、48dp 触控下限）、`styles.xml`（PageTitle/Section/Body/Caption + 主按钮/次按钮/分段栏/设置行样式）、`themes.xml`（状态栏与导航栏随纸白主题，浅色图标）。布局内**零硬编码色值**。
- **底部导航**：图标 + 文字四标签，选中态 = 主色图标/加粗文字 + 浅底胶囊 + `contentDescription="xx，已选中"`（读屏可识别当前项）；隐藏/恢复由阅读沉浸态驱动。
- **书库**：标题 + 圆角搜索框（占位「搜索书名、作者或 ISBN」、键盘搜索、清空、明确提交按钮）+ **热门 / 分类 / 猜你喜欢**分段栏 + 两列书封网格（≥600dp 自动三列）；首屏骨架、榜单空、搜索无结果、请求失败四态；滚动分页；切榜/搜索用 requestToken 丢弃旧响应；清空搜索恢复原榜单列表与滚动位置（不重复请求）。
- **我的书籍**：收藏/已下载分段 + 缩略图列表（56×76 封面、书名、作者、状态 chip、更多菜单）；取消收藏/删除下载走二次确认；两种空态 + 去书库；失败重试；分页与旧响应保护；从详情页返回后自动刷新（`MainActivity.onResume → markDirty`）。
- **阅读**：落地页优先展示上次阅读卡片（书名 + 继续阅读），无记录显示空态与去书库，打开失败显示错误与重试；沉浸阅读隐藏底部导航，顶部「退出阅读」胶囊可返回；WebView 注入登录 Cookie 与请求头；阅读位置由在线阅读网页自身保存（本地只存最后地址与书名）。
- **我的**：账户卡（骨架/内容/错误/未登录四态，刷新保留已加载内容）、今日下载额度卡（剩余 = 上限 − 已用，带进度条，未知时降级文案）、设置区（服务器地址弹窗：http(s) 校验 + 探活成功后保存；内容语言弹窗：en/zh/ru/es/fr/de 单选）、退出登录（低强调红色 + 二次确认 + 清栈回登录页）。

设计契约与各页 id 约定见 `DESIGN_UI.md`；四个页面共享顶部标题、分段栏、状态占位与设置行的样式（`styles.xml`）。

**v1.1.2（栏目调整）**：「最近上架」无内容，按需求由「分类」替换，并移除该栏目入口与请求。项目未持久化栏目选择，冷启动/升级后默认「热门」，旧的「最近上架」选中态不会残留。

**v1.1.4（分类降级说明，结论已在 v1.2.0 修正）**：v1.1.3 曾用「书籍语言」充当分类，但语言不是内容/体裁分类，按需求已移除。
v1.1.4 当时只核查了 Android 使用的 `/eapi/*` 接口，得出「书源没有可用的内容分类数据」并降级为
「当前书源暂未提供内容分类」。这个结论**只对 `/eapi` 成立**：网页版实际提供内容分类目录、分类书单、
语言筛选与分页（2026-11-07 实测），因此 v1.2.0 已改为「网页分类目录 → 原生分类界面」，见下节。

## 分类数据源（v1.2.0 修正）

### 结论边界（保留旧证据，修正过度推断）

| 结论 | 状态 | 证据 |
|---|---|---|
| `/eapi` 没有分类浏览端点（官方 App 1.11.4 的 37 个接口无分类接口，候选分类端点 404/400）；`most-popular` 列表 JSON 无分类字段 | **保留（仍成立）** | 旧核查 + 2026-11-07 复核一致 |
| `/eapi` 详情/搜索响应带 `categories`（分类 ID → 英文路径） | **新发现（此前记录未包含该字段）** | 2026-11-07 复核：`/eapi/book/search`、`/eapi/book/{id}/{hash}` 均返回 `categories`（如 `{'807':'History - Chinese History'}`），`most-popular` 列表仍无。该字段只能「按书查分类」，不能按分类反查书单，因此分类浏览仍由网页分类目录提供；eapi categories 用于交叉验证 |
| 「整个书源/网站都没有内容分类」 | **删除（错误推断）** | 网页版 `/categories` 服务端渲染 39 个一级分类 + 802 个二级分类 |
| 「网页搜索只有语言/格式/年份，没有题材分类入口」 | **删除（错误观察）** | 网页有完整分类目录与分类书单；`/categories` 与 `/category/*` 均为服务端渲染 |
| 「网页被 DiamWall 拦截，不能作为数据源」 | **修正** | 站点是 DiamWall 式 503 挑战（SHA-1 proof-of-work）；纯 Java HTTP 客户端按同一算法解算后可稳定取得分类 HTML，无需执行网页 JS |

### 数据链路（2026-11-07 真实联网实测，匿名）

| 路由 | 实测结果 |
|---|---|
| `GET https://<host>/categories` | 200，服务端渲染 HTML；39 个一级分类、802 个二级分类（父子关系与数量都在初始 HTML） |
| `GET /category/17`（History 一级） | 200，50 本/页；`pagerOptions.pagesTotal=3`，`baseUrl=/category/17/History?page=%number%` |
| `GET /category/17/History` 第 1/2/3 页 | 各 50 本；150 本 ID 去重后仍 150；第 4 页 200 但 0 本（网页最多 3 页） |
| `GET /category/270/Historical`（小说→历史小说） | 第 1 页含《三国演义（全二册）》(id 5625805，ISBN 9787020008728，termshash `82e26acf…`) |
| `GET /category/17/History/s/?languages[0]=chinese&page=1..3` | 各 50 本、language 全为 `Chinese`；150 本 ID 去重 150；分页 `baseUrl` 自带 `languages%5B0%5D=chinese&page=%number%` |
| `languages[0]=traditional chinese` | 独立筛选值：50 本全为 `Traditional Chinese`；与 `chinese` **不是同一个值**，不能合并 |
| `/category/270/Historical/s/?languages[0]=chinese` | 分类 + 语言 AND 成立：50 本全 Chinese，页面标题仍为 `Fiction - Historical` |
| 无结果（如 `languages[0]=nauru`） | **HTTP 200**（伪 200），有效书目 0；判空标记 `div.notFound` + 隐藏模板卡 |
| 不存在的分类 | **HTTP 200**（不是 404），`<title>Page not found</title>`，0 书目 |
| `GET /book/<token>/…html`（网页详情） | 513 DiamWall 浏览器验证（iframe 挑战），普通 HTTP 客户端取不到详情 HTML |
| 封面图（CDN） | 200 `image/jpeg`，普通 GET 可加载 |

**获取方式评估**：A（复用网页实际数据接口）→ 分类页没有可复用的分类 JSON 接口，页面本身就是服务端渲染；
B（初始 HTML 结构化书目）→ ✅ 可行，分类树与书目都在初始 HTML，无需 JS；C（必须执行 JS）→ 不需要。
反爬挑战的 SHA-1 proof-of-work 在客户端按页面脚本的等价逻辑计算（只算校验值、不执行页面脚本、
不绕过登录或付费限制），实现见 `net/WebCategorySource`。

### 分类模型与中文映射

- 保留网站原始分类树：一级 39 个、二级 802 个，用 `sourceCategoryId` + `parentId` 唯一确定节点；
  **不合并**多个网站分类，避免分页/去重错误（例如 `Fiction > Historical` id=270 与
  `Biography & Autobiography > Historical` id=77 同名但属于不同一级分类）。
- 每个分类保留 `sourceCategoryId / sourceName / displayName / parentId / sourceUrl / bookCount / type`；
  一级分类目录链接是 `/category/<id>`（无 slug），语言筛选地址以分类页 HTML 里 `searchForm` 的 action 为准
  （客户端首次打开书单时回填），不自造 `/category/17/s/` 这类会 404 的地址。
- 中文展示名映射见 `data/CategoryNames`：39 个一级分类 + Fiction/History/Biography 的二级分类有中文名，
  其余保留网站原名；**只做展示名映射，不按书名关键词猜分类，不用示例数据填充**。

### 语言筛选与分页

- 默认「全部语言」；语言与分类是 **AND**；切换分类或语言都重置到第 1 页。
- 语言取值来自网页筛选参数（与 `/eapi/info/languages` 的 175 个 key 一致）：
  `chinese` / `traditional chinese` / `english` / `japanese` / `russian` / `french` / `german` / `spanish`；
  界面展示为「简体中文 / 繁体中文 / …」，**简繁分开，不合并**。
- 下一页优先用页面 `pagerOptions.baseUrl`（自带分类 + 语言条件），不是客户端猜测拼接；
  每页 50 本；网站对分类浏览最多只给 3 页（约 150 本），这是数据源限制，不冒充全库。
- 旧响应保护：分类/语言/翻页请求都用递增 token 丢弃过期响应；3 页实测无重复，客户端仍保留分页去重与旧响应丢弃逻辑。

### 书目 → 详情映射结论（已验证）

- 网页书目字段：id / isbn / termshash / href / language / year / extension / filesize / 书名 / 作者 / 封面
  （HTML `z-bookcard` 属性 + slot 子元素）。
- **禁止**：网页 href 短 token 或 termshash 直接当 `/eapi/book/{id}/{hash}` 的 hash（实测返回
  `{"success":0,"error":"Invalid hash"}`；eapi hash 是 6 位十六进制，termshash 是 32 位，语义不同）。
- 可靠映射路径（独立复核抽查 10/10 命中、terms_hash 10/10 一致）：
  1) 网页卡片有 ISBN → `POST /eapi/book/search message=<ISBN>`；无 ISBN → 用完整标题检索；
  2) 只接受 **书籍 ID 完全相等** 的搜索结果（不按标题相似度关联其他版本）；
  3) 用该条目的 id+hash 请求 `/eapi/book/{id}/{hash}`；
  4) 校验详情 `terms_hash` 与网页 `termshash` 一致（缺失时退回 href 短 token 比对），通过才进入原生详情。
- 无法可靠映射的书：弹窗说明并提供「在浏览器打开」网页详情（网页详情页有 513 浏览器验证，
  因此不在 App 内做隐藏 WebView 自动化，也不把它包装成稳定接口）。
- 原生详情的收藏/下载/在线阅读沿用现有功能，前提是第 4 步校验通过；未通过不会强行打开原生详情。

### 界面与状态

- 三层：一级分类目录 → 二级分类（首行为「全部」= 该一级分类自身书单）→ 分类书单。
- 分类书单：分类路径标题 + 语言 chips + 两列书封网格（宽屏三列）+ 滚动加载更多。
- 状态区分：首次加载骨架；「当前条件没有结果」空态（附「部分书籍尚未分类，可通过搜索查找」）；
  网络失败；需要完成网页验证；页面结构变化解析失败；分类不存在 —— 不再统一显示「书源暂未提供内容分类」。
- 普通用户界面不展示接口名、JSON 字段或解析器堆栈。
- 更完整的 UI 逻辑（三层结构/状态机/交互矩阵/视觉规格/适配检查/候选问题清单）见 `分类UI设计逻辑说明.md`，可直接交给独立 review。

### 验证记录（2026-11-07）

- **真实联网（与 APK 同一份 Java 源码，JVM 直接运行）**：54/54 断言通过，日志
  `../research/zlweb/jvm-test/run3.log`；覆盖分类树 39/802、同名二级分类父级正确、
  历史分类 150 本无重复且含《史记》、历史小说含《三国演义》、历史+中文 3 页 150 本全 Chinese、
  历史小说+中文 AND、繁体独立筛选、空结果 vs 不存在分类 vs 解析失败区分、镜像站点动态挑战常量、
  中文映射、ISBN/href token/terms_hash 校验等。
- **独立复核**：`../research/zlweb/verify/VERIFY.md`（629 行，含原始 HTML/JSON 证据），
  关键补充：三个「伪 200」页面、挑战常量随镜像变化、详情页 513、eapi 映射 10/10。
- **最小闭环「历史 → 中文 → 第二页 → 详情」**（与 `BookMapper` 相同路径实测）：第二页前 6 本
  按 ISBN/标题检索均命中相同书籍 ID，原生详情 `terms_hash` **6/6 一致**；例如《万古江河》id 5722883
  → eapi hash `8b6ebe`，eapi categories `{'807': 'History - Chinese History'}`，与网页分类归属交叉吻合。
  证据：`../research/zlweb/acceptance-loop.md`。
- **构建**：见下方 v1.2.0 构建验证记录。
- **未做 Android 运行验证**：本环境无 Android 运行时/模拟器；构建成功不等于功能已真机通过。

## 常见问题（v1.1.1 已修复）

### 书库提示「书库加载失败 / Too many follow-up requests: 21」

**现象**：启动正常、能进主界面，但书库一直转圈后报 `Too many follow-up requests: 21`（或其它英文网络错误）。

**根因**：部分 Z-Library 镜像（实测 `z-lib.sk`、`z-library.sk`）对 `/eapi/book/*` 启用了 **DiamWall 反爬**：
服务端先返回 `307` 跳回同一地址，并下发 `Set-Cookie: __diamwall=…`；客户端必须保存该 Cookie 并回传才能通过。
旧版客户端有两个问题：

1. `HttpURLConnection` 默认自动跟随跳转，跳转发生在 Cookie 保存之前，于是无限 307 循环；Android 的 HTTP 栈（底层 OkHttp）跟到第 21 次抛 `Too many follow-up requests: 21`。
2. 启动探活只校验 `/eapi/info/ok`（该接口不受反爬保护），所以会选中"探活通过但书库被挑战"的镜像；此后失败也不会自动换服务器。

**v1.1.1 修复内容**：

- `Api` 关闭系统自动跳转，改为**手动有界跳转**（最多 5 跳）+ 跳转环检测（URL + 当前 Cookie 相同即判定为环），并**保存响应 Cookie 按域名回传**（`__diamwall` 挑战、`bsrv` 后端粘性都依赖它）——实测 `z-lib.sk` 的 `mostPopular` 已能返回 100 本书；
- 新增 `net/Hosts`：内置镜像改为可用性优先顺序（先放直连镜像，`sk` 系列作兜底），并提供**故障切换**；
- 书库错误页的「重试」会先自动探测并切换到其它可用镜像再重新加载，切换时提示「正在切换服务器… / 已切换到 xxx」；
- 网络/反爬错误统一翻译成中文提示（`服务器反爬校验未通过，请点击重试（会自动切换服务器）` 等），不再直接抛英文；
- 401/403/404 不触发换服务器（属登录/权限问题，会走登录失效流程）。

**如果仍然失败**：在「我的 → 设置 → 服务器地址」手动换一个镜像（弹窗会先探活，通过才保存），或回书库点「重试」自动切换。

## 构建

要求：Linux + JDK 17 + Android SDK（`platforms/android-33`、`build-tools/33.0.2` 里的 `d8.jar`）。
本项目**不用 Gradle**，直接 aapt2 → javac → d8 → zipalign → apksigner：

```bash
export ANDROID_HOME=/opt/android-sdk
bash tools/build.sh
# 产物：build/ZLibClone.apk（首次构建自动生成自签名证书 tools/zlibclone.jks，口令 zlibclone）
```

**arm64 主机注意**：SDK 自带的 `aapt2`/`zipalign` 是 x86_64 二进制，在 arm64 机器上无法执行。
本工程默认改用发行版原生的 `/usr/bin/aapt2` 与 `/usr/bin/zipalign`（Ubuntu: `apt install aapt zipalign apksigner`），
`d8`/`apksigner` 用 Java jar 版本，跨架构可用。也可用环境变量覆盖：`AAPT2_BIN`、`ZIPALIGN_BIN`、`APKSIGNER_BIN`。

### v1.2.1 构建与验证记录（分类 UI/UX 改造）

- 产物：`ZLibClone-1.2.1.apk`，155,690 B，SHA-256 `5a410b51…72c9a51`，versionCode 8 / versionName 1.2.1
- 变更（只改分类模块 UI/交互层与资源，不改分类数据链路）：
  - 顶栏联动：分类 L0 显示书库标题 + 全库搜索框；钻取到 L1/L2 时 `LibraryView.setChromeCompact(true)` 收紧为 36dp 分段栏，离开分类恢复；
    L0 隐藏重复的标题/面包屑，只保留「共 N 个一级分类 · 部分未分类书籍可通过顶部搜索查找」；
  - 层级导航：固定 48dp 双段面包屑（`‹ 全部分类` 回 L0；`/ 一级分类` 回 L1）；L1 首行「浏览全部 · 一级分类」、L2 标题「一级分类 · 全部」；
  - 语言筛选：9 个预创建 Chip（48dp 触控热区 + 32dp 胶囊，选中主色加粗/未选中次要墨色，选中自动居中），切换语言不再重建 View；
    L2 切语言保留旧书单 α=0.4 过渡（禁用滚动/点击），返回后恢复并回到列表顶部，避免骨架闪烁；
  - 骨架/状态：骨架拆为列表形态（L0）与网格形态（L2）；L2 末页显示「已展示该分类前 N 本精选藏书 · 更多书籍请使用顶部搜索」尾注；
  - 导航记忆：L0/L1 列表滚动位置精准恢复；分类→热门复用缓存、不重复请求；返回上级时作废在途分类请求，避免旧回调覆盖当前层级 UI；
  - 详情映射：无 hash 书目使用 `LruCache(64)` 缓存 + 可取消加载弹窗，取消后递增 token 丢弃回调；分类错误「重试」接镜像故障切换；
  - 逐文件改动与资源 ID 对照见 `分类UI改造-变更说明.md`，可合入补丁见 `分类UI改造-v1.2.0.diff`（`patch -p1` 对 v1.2.0 基线 0 冲突）。
- 验证：aapt2 compile/link + javac + d8 + zipalign(4) + apksigner(v1+v2+v3) 全部通过；
  `aapt dump badging` 确认 versionCode 8 / versionName 1.2.1；独立评审发现的 1 个高危状态机缺陷（在途分类请求覆盖当前层级）已修复。
- 未做真机运行验证：本环境无 Android 运行时，分类页手势、滚动恢复手感、骨架/尾注/弹窗视觉需真机确认。

### v1.2.0 构建与验证记录（分类功能）

- 产物：`ZLibClone-1.2.0.apk`，147,010 B，SHA-256 `1ac30a96…80c881`，versionCode 7 / versionName 1.2.0
- 变更：
  - 新增 `net/WebCategorySource`（网页分类目录/分类书单/语言筛选/分页/反爬 PoW 解算，纯 Java）、
    `net/BookMapper`（网页书目 → eapi 详情的 ID 全等 + terms_hash 校验映射）、
    `model/Category`、`model/CategoryPage`、`data/CategoryNames`、`ui/CategoryView`、
    `layout/view_categories.xml`、`layout/row_category.xml`；
  - `LibraryView` 的「分类」分段从「不可用说明页」改为真实分类视图；
    `data/Categories` 从「isAvailable() 恒 false」改为「数据源已实测 + 语言取值」，实际可用性由请求结果区分；
  - `model/Book`、`net/Api` 增加 `isbn`、`termsHash`（详情 `terms_hash`）、`webUrl` 字段。
- JVM 真实联网测试 54/54 通过（见「分类数据源」验证记录）；
  `aapt2 dump badging` 版本正确、`zipalign -c 4` 通过、`apksigner verify`（v1+v2+v3）通过。
- 未做真机运行验证：分类界面、浏览器回退的真机表现未测；如遇崩溃请提供
  `files/last_crash.txt` 与 `adb logcat`。

### v1.1.4 构建验证记录（分类降级说明）

- 产物：`ZLibClone-1.1.4.apk`，118,189 B，SHA-256 `d7ea79a3…cb4338c`，versionCode 6 / versionName 1.1.4
- 变更：移除 v1.1.3 的「语言当分类」实现（分类网格、分类书单、语言筛选、`browseByLanguage` 等全部删除）；
  分类页改为「当前书源暂未提供内容分类」+ 缺失字段/接口说明 +「去热门」；不发分类请求、不拼凑分类
- 核查：列表/详情字段、10 个候选分类端点、官方 App 接口面、网页版、外部 OpenLibrary ISBN 匹配率（10%）——结论边界与修正见「分类数据源（v1.2.0 修正）」
- 构建/签名/对齐与静态检查：见下方命令与结果

### v1.1.3 构建验证记录（语言分类，已在 v1.1.4 移除）

- 产物：`ZLibClone-1.1.3.apk`，122,358 B，SHA-256 `e9f80ebd…99b3cc`，versionCode 5 / versionName 1.1.3
- 真实接口验证（JVM 直接调用同一 `Api.java`）：
  - `languages()` → 175 种语言；`Categories.build()` → 12 个一级分类（中文/英文/俄文/日文/法文/德文/西班牙文/意大利文/葡萄牙文/荷兰文/波兰文/土耳其文）
  - `browseByLanguage("chinese", 1/2)` → 每页 20 本、共 25 页、页间无重复；`english` 同样正常；分类书单 → 书籍详情链路正常
- 构建/签名/对齐与静态检查：见下方命令与结果

### v1.1.2 构建验证记录（分类栏目调整）

- 产物：`ZLibClone-1.1.2.apk`，118,189 B，SHA-256 `7abdde23…ccef6c3`，versionCode 4 / versionName 1.1.2
- 变更：书库分段栏改为「热门 / 分类 / 猜你喜欢」；移除「最近上架」入口与 `recentlyAdded` 请求；新增分类缺失态与 `data/Categories.java`
- 数据核验：线上 API 书籍 JSON 字段无分类/主题字段（`author/cover/hash/id/isbn/title/url`、详情含 `language/content_type/...`），分类端点 404，故按需求显示不可用
- 构建/签名/对齐与静态检查：见下方命令与结果

### v1.1.1 构建验证记录（网络修复）

- 产物：交付 `ZLibClone-1.1.1.apk`，118,189 B，versionCode 3 / versionName 1.1.1，SHA-256 `79ec4cb1…ac2fd9a`
- 真实服务器验证（JVM 直接调用同一个 `Api.java`）：`https://z-lib.sk/`（DiamWall 镜像）`mostPopular` 返回 100 本书、`book` 详情正常、`/file` 返回 `Please login`（未登录的预期）；`z-library.sk` 同样通过
- 构建/签名/对齐校验：`bash tools/build.sh`、`zipalign -c 4`、`apksigner verify`（v1+v2+v3）全部通过；dex 140 类（新增 `Hosts`，无第三方依赖）

### v1.1 构建验证记录（UI 改版）

- 产物：`build/ZLibClone.apk` → 交付 `ZLibClone-1.1.0.apk`，118,189 B，SHA-256 `25453e75…0eb605a6`，versionCode 2 / versionName 1.1.0
- dex：136 个类（零第三方依赖，无 androidx/okhttp）；工程 22 个 Java 文件 / 5,474 行 / 14 个布局
- 校验：`zipalign -c 4` 通过；`apksigner verify` 通过（v1+v2+v3，`CN=ZLibClone`）；`aapt2 dump badging` 包名/版本/权限/启动 Activity 正确
- 静态一致性检查：布局 id ↔ Java `findViewById` 类型全部匹配（无 ClassCastException 风险）；每个类使用的 id 都属于它 inflate 的布局（无 null 风险）；全布局零硬编码色值；无遗留旧 id/旧 string key
- **未做真机/模拟器运行验证**：本环境无 Android 运行时，四页截图请安装后查看；运行期问题看 `files/last_crash.txt`

### 本次构建验证记录

- 工具链：JDK 17.0.20 + aapt2 2.19(arm64) + d8 3.3.20 + zipalign(arm64) + apksigner 0.9
- 产物：`build/ZLibClone.apk`，70,950 B（含修复后重新构建），dex 类 106+，零第三方依赖（dex 中无 androidx/okhttp 类）
- 校验：`zipalign -c -v 4` 通过；`apksigner verify` 通过（v1+v2+v3，证书 `CN=ZLibClone`）；`aapt2 dump badging` 包名/版本/权限/启动 Activity 均正确
- 网络层联调：用 `mock/mock_server.py` 在 JVM 桩环境跑通 **24 项断言全过**（探活、3 个榜单、搜索、详情、格式、下载直链、收藏/取消、saved/downloaded、登录/资料、401/404 错误路径、header 契约、absolute 拼接）

### 首屏与稳定性说明

- 启动页对内置域名串行探活，单个 4s、**总预算 30s**；超时进入失败页，可重试或手填服务器地址（如 `http://127.0.0.1:8765/` 走 Mock）。
- `ReaderView` 在 Activity `onDestroy` 时会 `destroy()` 内嵌 WebView，避免旋转/反复进出阅读页泄漏内存。
- 未内置 Tor（见下节差异表）：`.onion` 地址无法解析；如需匿名访问请用系统级 VPN/Orbot 全局代理。

### 真机排错

崩溃时 `App` 的兜底 handler 会把堆栈写到应用私有目录 `files/last_crash.txt`
（`adb shell run-as com.zlib.clone cat files/last_crash.txt`，或 root 文件管理器）。
如遇问题，把该文件内容 + `adb logcat -s ZLibClone AndroidRuntime` 输出发回即可。

## 安装与使用

1. 把 `build/ZLibClone.apk` 传到手机安装（需允许「安装未知来源应用」）。
2. 打开后 Splash 会探测服务器；若全部超时，在登录页手填服务器地址（如 `https://zlibrary.se/`）。
3. 用你的 Z-Library 账号登录即可浏览/下载（受服务端账号额度限制）。

### 无真实服务时的全链路自测（Mock 模式）

```bash
python3 mock/mock_server.py 8765        # 在手机/同一网络机器上启动
```
App 的「服务器地址」填 `http://127.0.0.1:8765/`，登录任意邮箱+密码，即可跑通
列表/搜索/详情/收藏/下载/阅读全部链路（Mock 返回 3 本示例书与一个合法 epub 文件）。
Mock 服务端会把客户端发来的 Authorization / remix 头 / Cookie / 版本头打到日志里，
可用来核对客户端是否符合逆向出的契约。

## 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/zlib/clone/
│   ├── App.java                  Application（Prefs 初始化）
│   ├── net/Api.java              全部 /eapi 接口 + 请求头 + JSON 解析
│   ├── net/ApiException.java
│   ├── net/WebCategorySource.java 网页分类目录/书单/语言筛选/分页 + 反爬挑战解算（纯 Java）
│   ├── net/BookMapper.java       网页书目 → /eapi 详情（ID 全等 + terms_hash 校验）
│   ├── model/{Book,User,Format,DownloadInfo,Page}.java
│   ├── model/Category.java       分类节点（来源 ID / 父级 / 中文名 / 来源链接）
│   ├── model/CategoryPage.java   分类书单分页结果
│   ├── data/{Prefs,Task}.java    存储与线程工具
│   ├── data/CategoryNames.java   分类中文展示名映射
│   └── ui/                       Activity / View / Adapter / 图片加载（含 CategoryView 分类界面）
└── res/                          布局、文案、主题、图标
tools/build.sh                    构建脚本
tools/zlibclone.jks               构建时生成的签名证书（口令 zlibclone）
mock/mock_server.py               Mock /eapi 服务端
SPEC.md                           项目接口契约（CORE 与 UI 的约定）
分类UI设计逻辑说明.md             分类 UI 逻辑/状态机/交互矩阵/待 review 问题（自包含）
```

## 与官方客户端的差异（有意为之）

| 项 | 官方 1.11.4 | 本复刻 |
|---|---|---|
| Tor | 内置 tor-android + onion 兜底 | **未内置 Tor**（体积/复杂度原因）。需要匿名访问时请用系统级 Tor/VPN（Orbot + 全局代理） |
| 技术栈 | Kotlin + AndroidX + Room + Dagger + RxJava2 | 纯 Java + framework（APK 约 100 KB） |
| 域名切换 | 反射改 Retrofit 私有 baseUrl | 每请求按当前 base 拼绝对 URL |
| 下载 | 系统 DownloadManager（不走 Tor） | 同左（改进留作后续：OkHttp 流式下载） |
| 扫码登录 | CameraX + ML Kit + AES token | **未实现**（可后续加：AES/CBC 零 IV + key `gvcypitjpsvdlmen`，格式 `token;domain...`） |
| 自更新 | `eapi/info/android-app` | 未实现（自行发版） |
| 数据缓存 | Room 10 张表 | 内存 + SharedPreferences（简化） |

## 已知限制

- 运行时未在本机验证（该环境无 Android 运行时/模拟器），代码经静态编译与 Mock 服务端联调校验；
  真机若遇崩溃请提供 logcat。
- 未内置 Tor：`.onion` 地址无法直连；clearnet 域名在部分地区被运营商/DNS 屏蔽，需自备网络条件。
- 只实现了官方客户端的主干功能；QR 扫码登录、Kindle 发送、捐赠、语言包、自更新等未实现。
- 服务端 `/eapi` 属对方私有接口，可能随时变更；本文实现对应 1.11.4 时的契约。
- 分类数据来自网页版：网站改版或反爬策略变化时分类可能暂时不可用，界面会区分
  「网络失败 / 需要完成网页验证 / 页面结构变化 / 分类不存在」。
- 分类浏览每个分类最多只能翻网页给出的 3 页（匿名实测约 150 本），不是全库分类总量；
  未被网站分类的书籍仍可通过搜索查找。
- 网页详情页有 513 DiamWall 浏览器验证：App 内不做隐藏 WebView 自动化；无法可靠映射到原生详情的书，
  使用系统浏览器打开网页详情。
- 分类界面（含浏览器回退）未做真机运行验证（本环境无 Android 运行时/模拟器）。

## 合规声明

- 本工程仅用于**协议与客户端行为的工程复现**，不含任何受版权保护的内容，不提供账号、不绕过任何付费/额度限制。
- Z-Library 的服务在多个司法辖区存在版权争议；下载/分发受版权保护的作品的法律风险由使用者自行承担。
- 不得使用本工程冒充官方客户端，不得复用原作者的签名证书、Firebase/Crashlytics 凭据或 `Authorization` 之外的私有凭据。
