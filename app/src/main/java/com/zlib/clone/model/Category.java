package com.zlib.clone.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容分类（一级或二级）。
 *
 * 数据来自 Z-Library 网页版分类目录（服务端渲染的 HTML，见 net/WebCategorySource），
 * 不是 /eapi 接口。字段含义：
 * - sourceCategoryId：网站分类 ID（查询/关联用，不能只靠名称，因为不同一级分类下可能有同名二级分类，
 *   例如 Fiction > Historical 与 Biography & Autobiography > Historical）；
 * - sourceName：网站原始英文名；
 * - displayName：界面展示名（中文映射见 data/CategoryNames；无映射时等于 sourceName）；
 * - parentId：一级分类为 0，二级分类为所属一级分类的 sourceCategoryId；
 * - sourceUrl：网站在分类目录里给出的相对链接，例如 /category/17/History；
 * - bookCount：子分类链接文本里的书目数量，解析不到时为 -1；
 * - type：网页 data-type（fiction / non-fiction），可能为空串；
 * - children：二级分类列表（一级分类才有）。
 */
public class Category {

    public int sourceCategoryId;
    public String sourceName = "";
    public String displayName = "";
    public int parentId;
    public String sourceUrl = "";
    public int bookCount = -1;
    public String type = "";
    public List<Category> children = new ArrayList<Category>();

    /**
     * 页面实际使用的筛选表单地址，例如 /category/17/History/s/。
     * 一级分类在目录里的链接只有 /category/17（没有 slug），单独拼 /category/17/s/ 会 404；
     * 因此语言筛选的 URL 以分类页 HTML 里 searchForm 的 action 为准（首次打开书单时回填）。
     */
    public String searchBase = "";

    public boolean isRoot() {
        return parentId <= 0;
    }

    /** 界面主标题：有中文映射用中文，否则保留网站原名。 */
    public String title() {
        return (displayName == null || displayName.length() == 0) ? sourceName : displayName;
    }

    /** 是否与网站原名不同（用于决定是否展示英文副标题）。 */
    public boolean hasChineseName() {
        return displayName != null && displayName.length() > 0
                && !displayName.equals(sourceName);
    }
}
