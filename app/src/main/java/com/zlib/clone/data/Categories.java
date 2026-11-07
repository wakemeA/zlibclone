package com.zlib.clone.data;

/**
 * 「分类」数据来源判定与语言筛选取值（2026-11 修正版）。
 *
 * 重要修正：此前 v1.1.4 的结论「当前书源暂未提供内容分类」只对 /eapi 接口成立，
 * 不能推广为「整个书源/网站没有分类」。实测（可复现，证据见 README「分类数据源」）：
 *
 * 1) /eapi 字段与端点确实没有体裁分类：
 *    - 列表 JSON：author / cover / coverData / hash / id / isbn(空) / title / url；
 *    - 详情 JSON：identifier / language / content_type(恒为 book) / year / extension / publisher /
 *      series / description / pages / filesize / md5 / sha256 / terms_hash 等，无 genre/category/subject；
 *    - 10 个候选分类端点全部 404/400；官方 App 1.11.4 的 37 个接口也没有分类接口。
 *    这段结论保留不变。
 *
 * 2) 网页版提供真实的内容分类浏览：
 *    - GET /categories 服务端渲染 HTML，含 39 个一级分类、约 802 个二级分类，带父子关系与数量；
 *    - GET /category/{id}/{slug} 服务端渲染分类书单（<z-bookcard>），带语言筛选 languages[] 与分页；
 *    - 站点有 DiamWall 式反爬（首次 503 + SHA-1 proof-of-work 挑战），纯 HTTP 客户端可解算后正常获取
 *      （见 net/WebCategorySource，不执行网页脚本、不绕过登录/付费限制）。
 *    因此「网站没有分类」的判断已删除；分类功能改为网页分类数据源 + 原生界面（ui/CategoryView）。
 *
 * 3) 实际可用性必须由请求结果决定，不再用恒为 false 的开关永久禁用：
 *    {@link #hasContentCategorySource()} 只表示「存在已实测的数据源」，网络失败、需要过验证、
 *    页面改版、分类为空等状态由 WebCategorySource 的异常类型和界面状态分别表达。
 */
public final class Categories {

    /**
     * 语言筛选的真实取值（网页 GET 参数 languages[]，以 /eapi/info/languages 与网页筛选数据实测）：
     * 空串 = 不限语言；Chinese 与 Traditional Chinese 是**两个不同**的筛选值，不能合并成一个参数。
     * 界面展示名见 {@link CategoryNames#languageDisplayName(String)}。
     */
    public static final String[] LANGUAGE_VALUES = {
            "", "chinese", "traditional chinese", "english", "japanese",
            "russian", "french", "german", "spanish"
    };

    /** 是否已有内容分类数据源：网页分类目录（已实测）。实际成功与否看请求结果。 */
    public static boolean hasContentCategorySource() {
        return true;
    }

    private Categories() {
    }
}
