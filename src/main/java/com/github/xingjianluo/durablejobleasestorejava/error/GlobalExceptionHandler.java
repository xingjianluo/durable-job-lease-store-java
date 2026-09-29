package com.github.xingjianluo.durablejobleasestorejava.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：{ errorCode, message, timestamp }，调用方按 errorCode 分支处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        ErrorCode code = ex.getCode();
        log.info("业务拒绝 code={}, message={}", code, ex.getMessage());
        return ResponseEntity.status(code.getHttpStatus()).body(body(code.name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body("INVALID_REQUEST_BODY", "请求体不是合法 JSON 或字段类型错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", ex.getMessage()));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", code);
        m.put("message", message);
        m.put("timestamp", Instant.now().toString());
        return m;
    }
}
