package com.bai.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MultipartException;

/**
 * 统一异常出口：业务状态与 HTTP 状态分离
 */
@RestControllerAdvice
public class BaiExceptionHandler {

    @ExceptionHandler(BaiException.class)
    public ResponseEntity<BaiResponse<Void>> handle(BaiException e) {
        return ResponseEntity.status(resolveStatus(e.getCode()))
                .body(BaiResponse.fail(null, e.getCode(), e.getMessage()));
    }

    /** 缺参/缺文件/缺请求头/非 multipart → HTTP 400（各原子 *001 码由 Controller 语义补充，此处保底） */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MissingRequestHeaderException.class, MissingRequestValueException.class, MultipartException.class})
    public ResponseEntity<BaiResponse<Void>> handleMissing(Exception e) {
        return ResponseEntity.badRequest()
                .body(BaiResponse.fail(null, "PARAM_MISSING", "请求参数缺失或格式非法: " + e.getMessage()));
    }

    private HttpStatus resolveStatus(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (code.endsWith("1008")) {
            return HttpStatus.TOO_MANY_REQUESTS; // 429 + Retry-After 由各原子补充
        }
        if (code.endsWith("1001") || code.endsWith("1005") || code.endsWith("1006") || code.endsWith("1007")
                || code.endsWith("4001") || code.endsWith("4002")) {
            return HttpStatus.BAD_REQUEST; // 含 4xxx 段场景类非法（如 FST_4001）
        }
        if (code.endsWith("1002")) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code.endsWith("1003")) {
            return HttpStatus.FORBIDDEN;
        }
        if (code.endsWith("1004")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.endsWith("4101") || code.endsWith("4102") || code.endsWith("4103") || code.endsWith("4104")) {
            return HttpStatus.UNPROCESSABLE_ENTITY; // 422：业务规则阻断（勾稽不平/票据状态异常/审单超期/制裁命中等）
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
