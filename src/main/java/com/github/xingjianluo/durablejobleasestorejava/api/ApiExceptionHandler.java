package com.github.xingjianluo.durablejobleasestorejava.api;

import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务异常统一转成 {code,message}，HTTP 状态随错误码而定，调用方可据 code 区分失败原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(JobApiException.class)
    public ResponseEntity<Map<String, String>> handle(JobApiException e) {
        log.info("业务拒绝 code={} msg={}", e.code(), e.getMessage());
        return ResponseEntity.status(e.code().httpStatus())
                .body(Map.of("code", e.code().name(), "message", e.getMessage()));
    }
}
