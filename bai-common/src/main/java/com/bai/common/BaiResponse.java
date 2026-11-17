package com.bai.common;

import java.io.Serializable;

/**
 * /api/bai/** 统一响应结构（提示词库约定）：{requestId, code, message, data}
 * <p>code：成功为 "0"；业务状态与 HTTP 状态分离（400/401/403/404/429/500）
 */
public class BaiResponse<T> implements Serializable {

    private String requestId;
    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> BaiResponse<T> ok(String requestId, T data) {
        BaiResponse<T> r = new BaiResponse<>();
        r.requestId = requestId;
        r.code = "0";
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> BaiResponse<T> fail(String requestId, String code, String message) {
        BaiResponse<T> r = new BaiResponse<>();
        r.requestId = requestId;
        r.code = code;
        r.message = message;
        return r;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
