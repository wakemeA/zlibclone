package com.zlib.clone.net;

/**
 * 接口异常。
 *
 * httpCode：
 * - > 0：服务端返回的 HTTP 状态码（401/403 时上层应回登录页；400-599 已抽取 JSON error）
 * - 0  ：网络层异常（连接失败、超时、DNS 等，此时 serverError 为 null）
 * serverError：服务端 JSON 里的 "error" 字段（没有则为 null）。
 */
public class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    public final int httpCode;
    public final String serverError;

    public ApiException(int httpCode, String message, String serverError) {
        super(message);
        this.httpCode = httpCode;
        this.serverError = serverError;
    }
}
