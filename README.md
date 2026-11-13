<div align="center">
  <img src="app/src/main/res/drawable/ic_launcher.png" width="88" height="88" alt="ZLibClone 图标">
  <h1>ZLibClone</h1>
  <p>轻量的 Android Z-Library 非官方客户端复刻。</p>
  <p>搜索图书 · 分类浏览 · 收藏下载 · 在线阅读</p>

  <a href="https://github.com/wakemeA/zlibclone/releases/latest"><img src="https://img.shields.io/github/v/release/wakemeA/zlibclone?label=version&color=2F5D4D" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Android-5.0%2B-2F5D4D" alt="Android 5.0 及以上">
  <img src="https://img.shields.io/badge/Java-零第三方库依赖-2F5D4D" alt="Java，零第三方库依赖">

  <p>
    <a href="https://github.com/wakemeA/zlibclone/releases/latest/download/ZLibClone.apk"><strong>下载 APK</strong></a> ·
    <a href="https://github.com/wakemeA/zlibclone/releases">更新记录</a> ·
    <a href="https://github.com/wakemeA/zlibclone/issues">问题反馈</a>
  </p>
</div>

> **非官方项目，与 Z-Library 无隶属关系。** 不内置账号或书籍内容，不提供书源服务，不绕过登录、付费或下载额度限制。

## 核心亮点：更快打开

**原版 Z-Library 打开需要 60 秒或更久，ZLibClone 约 8 秒。**

> 数据来自用户实际使用反馈，非统一条件下的性能基准测试；实际耗时受设备、网络和服务器状态影响。

## 功能

- **找书**：热门推荐、关键词搜索，查看书籍详情与可用格式。
- **分类**：一级 / 二级分类目录，支持语言筛选与分页浏览。
- **收藏与下载**：管理收藏和下载记录，通过系统下载管理器保存文件。
- **在线阅读**：打开服务端提供的阅读页面，支持继续上次阅读。
- **账户与连接**：邮箱登录、查看下载额度、自动探测和手动切换服务器。
- **原生轻量**：纯 Java + Android 系统组件，暖纸白与墨绿配色，无第三方库依赖。

## 下载安装

**[直接下载最新版 APK](https://github.com/wakemeA/zlibclone/releases/latest/download/ZLibClone.apk)** · [查看全部版本](https://github.com/wakemeA/zlibclone/releases)

- 系统要求：Android 5.0 及以上。
- 安装时按系统提示允许浏览器或文件管理器「安装未知应用」。
- 覆盖升级需要签名一致；自行编译的安装包通常不能直接覆盖 Release 版本。

### 开始使用

1. 安装并打开 App，等待自动探测服务器；连接失败时手动填写可信的 HTTPS 服务器地址。
2. 使用自己的 Z-Library 邮箱和密码登录。
3. 在「书库」搜索或浏览分类，在「我的书籍」管理收藏与下载，在「我的」查看额度和连接设置。

连接失败时，可先点击「重试」，或前往「我的 → 服务器地址」切换服务器。只向可信服务器提交账号信息。

## 使用前须知

- 功能依赖第三方服务及网络环境，接口或网页改版可能导致部分功能不可用。
- 分类列表受网站分页限制，不代表完整藏书；找不到时可尝试搜索。部分书籍详情会转到系统浏览器。
- 未内置 Tor，不支持直接连接 `.onion`；扫码登录、Kindle 发送和应用内自动更新暂未实现。
- **开发侧已完成编译、签名及部分接口测试，尚未完成系统性真机兼容性验证。** 欢迎反馈设备、Android 版本和复现步骤；请勿提交密码、Cookie 或登录令牌。

## 从源码构建

需要 Linux、JDK 17、Android SDK Platform 33、Build Tools 33.0.2，以及本机可执行的 `aapt2`、`zipalign`、`apksigner` 和 `zip`。本项目使用脚本构建，**不使用 Gradle**。

```bash
git clone https://github.com/wakemeA/zlibclone.git
cd zlibclone
export ANDROID_HOME=/path/to/android-sdk
bash tools/build.sh
```

产物为 `build/ZLibClone.apk`（本地生成，不提交到仓库）。脚本默认从 `/usr/bin` 加载 `aapt2`、`zipalign` 和 `apksigner`，可通过 `AAPT2_BIN`、`ZIPALIGN_BIN`、`APKSIGNER_BIN` 指定其他位置；`D8_BIN` 可覆盖默认的 SDK Build Tools D8 路径。ARM64 主机需使用对应架构的可执行工具。

首次构建会生成本地开发签名，仅用于开发测试。签名密钥已加入忽略规则，请自行妥善保管；正式分发应使用自己的安全签名配置。

### 离线回归测试

需要 Python 3 和可用的 `java` / `javac`，无需真实账号或网络服务：

```bash
python3 tools/test_batch1.py
```

测试从生产源码提取方法体，用确定性的 JVM 测试替身验证异常恢复与请求状态；它不替代 Android 真机、系统弹窗、下载或真实接口测试。

## 开发文档

- [更新记录与验证范围](<CHANGELOG.md>)
- [技术说明与历史验证记录](<TECHNICAL_NOTES.md>)
- [接口与模块约定](<SPEC.md>) · [界面设计](<DESIGN_UI.md>)
- [分类交互逻辑](<分类UI设计逻辑说明.md>) · [分类改版记录](<分类UI改造-变更说明.md>)
- [本地 Mock 服务](<mock/mock_server.py>)：仅用于开发测试，请勿使用真实账号或向公网开放；日志会记录请求中的认证头。

## 声明

本项目用于客户端与协议行为的工程学习和复现，不冒充官方客户端。请仅访问、下载和传播已获授权的内容，并遵守所在地法律及服务条款。
