package com.github.xingjianluo.durablejobleasestorejava.error;

/**
 * 携带明确错误码与可读原因的业务异常。
 */
public class JobApiException extends RuntimeException {
    private final ErrorCode code;

    public JobApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
