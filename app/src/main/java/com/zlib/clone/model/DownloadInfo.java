package com.zlib.clone.model;

/**
 * 下载链接信息，来自 GET /eapi/book/{id}/{hash}/file 的 file 对象。
 * url 的 JSON 名是 "downloadLink"。
 */
public class DownloadInfo {

    public String url;
    public String description;
    public String extension;
    public boolean allowDownload;
}
