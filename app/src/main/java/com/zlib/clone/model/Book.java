package com.zlib.clone.model;

/**
 * 书籍模型：单书详情与列表页共用。
 *
 * 列表接口（most-popular / recently / recommended）返回的是"简本"，
 * 可能只带 id/title/author/cover/hash，其余字段缺省；
 * 因此解析侧必须容忍字段缺失（字符串兜底 ""、数字兜底 0、布尔兜底 false）。
 *
 * 注意 JSON 名陷阱：
 * - isUserSavedBook 的 JSON 名是 "_isUserSavedBook"（带下划线前缀）
 * - dateDownload    的 JSON 名是 "date_download"
 */
public class Book {

    public int id;

    public String title;
    public String author;
    public String cover;
    public String hash;
    public String extension;
    public String filesizeString;

    /**
     * 网页分类页书目额外字段（/eapi 的列表/详情里没有直接可用的对应值）：
     * - isbn：网页 z-bookcard 的 ISBN（部分书为空或为 ASIN），仅用于「按 ISBN 检索 → 同 ID 校验」的详情映射；
     * - termsHash：网页 termshash 与 /eapi 详情的 terms_hash 同值，用于映射完成后的身份校验；
     * - webUrl：网页书籍详情绝对地址（原生映射失败时作为网页详情回退入口）。
     * 注意：termsHash **不是** /eapi/book/{id}/{hash} 的 hash（实测 Invalid hash），禁止直接当 hash 用。
     */
    public String isbn;
    public String termsHash;
    public String webUrl;

    public String year;
    public String language;
    public String description;
    public String href;
    public String dl;
    public String preview;
    public String readOnlineUrl;
    public String dateDownload;

    public long filesize;

    public boolean readOnlineAvailable;
    public boolean isUserSavedBook;
    public boolean sendToEmailAvailable;
    public boolean kindleAvailable;
}
