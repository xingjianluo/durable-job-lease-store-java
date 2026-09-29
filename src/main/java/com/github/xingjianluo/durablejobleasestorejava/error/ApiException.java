package com.github.xingjianluo.durablejobleasestorejava.error;

/**
 * 业务异常：携带稳定的机器可读错误码。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.getDefaultMessage());
        this.code = code;
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
