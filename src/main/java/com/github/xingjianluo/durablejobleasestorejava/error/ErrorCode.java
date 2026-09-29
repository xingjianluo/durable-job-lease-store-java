package com.github.xingjianluo.durablejobleasestorejava.error;

import org.springframework.http.HttpStatus;

/**
 * 业务失败码：调用方依据 code 区分并发修改冲突、租约冲突、重复提交等情况。
 */
public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    ILLEGAL_TRANSITION(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    LEASE_CONFLICT(HttpStatus.CONFLICT),
    LEASE_EXPIRED(HttpStatus.CONFLICT),
    LEASE_TAKEN_OVER(HttpStatus.CONFLICT),
    RETRY_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    ALREADY_COMPLETED(HttpStatus.CONFLICT),

    CURSOR_INVALID(HttpStatus.BAD_REQUEST),
    CURSOR_EXPIRED(HttpStatus.GONE),
    CURSOR_ENTRY_DELETED(HttpStatus.GONE);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
