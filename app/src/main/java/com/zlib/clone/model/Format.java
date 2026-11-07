package com.zlib.clone.model;

/**
 * 单本书的可用下载格式，来自 GET /eapi/book/{id}/{hash}/formats 的 books 数组。
 */
public class Format {

    public int id;

    public String hash;
    public String extension;
    public String filesizeString;

    public long filesize;
}
