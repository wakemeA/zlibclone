package com.zlib.clone.model;

/**
 * 登录用户模型，字段与 /eapi/user 返回的 JSON 对齐。
 *
 * 注意 JSON 名陷阱：
 * - remixUserkey   的 JSON 名是 "remix_userkey"（下划线）
 * - kindleEmail    的 JSON 名是 "kindle_email"
 * - donationsExpire 的 JSON 名是 "donations_expire"
 * - downloadsToday 的 JSON 名是 "downloads_today"
 * - downloadsLimit 的 JSON 名是 "downloads_limit"
 * - donationsActive 的 JSON 名是 "donations_active"
 * - isConfirmed    的 JSON 名是 "confirmed"
 */
public class User {

    public int id;

    public String email;
    public String name;
    public String remixUserkey;
    public String kindleEmail;
    public String donationsExpire;

    public int downloadsToday;
    public int downloadsLimit;
    public int donationsActive;
    public int isPremium;
    public int isConfirmed;
}
