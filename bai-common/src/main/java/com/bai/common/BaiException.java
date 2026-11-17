package com.bai.common;

/**
 * 原子业务异常：code 为原子错误码段（如 OCR_1005），message 为处理提示
 */
public class BaiException extends RuntimeException {

    private final String code;

    public BaiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
