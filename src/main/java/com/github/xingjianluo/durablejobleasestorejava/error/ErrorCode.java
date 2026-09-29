package com.github.xingjianluo.durablejobleasestorejava.error;

import org.springframework.http.HttpStatus;

/**
 * 调用方可据 code 精确区分失败原因。
 */
public enum ErrorCode {

    // ---------- 资源 ----------
    TASK_NOT_FOUND("任务不存在", HttpStatus.NOT_FOUND),

    // ---------- 并发 / 租约（三类必须可区分） ----------
    /** 并发修改冲突：CAS 版本不匹配，如两个执行者并发领取同一任务。 */
    CONCURRENT_MODIFICATION("并发修改冲突：任务已被其他执行者领取或状态已变更", HttpStatus.CONFLICT),
    /** 租约冲突：令牌不对 / 已被他人接管。 */
    LEASE_CONFLICT("租约冲突：租约令牌无效或已被其他执行者接管", HttpStatus.CONFLICT),
    /** 租约已过期。 */
    LEASE_EXPIRED("租约已过期：任务已被系统回收", HttpStatus.CONFLICT),

    // ---------- 幂等 ----------
    /** 重复提交：任务已处于终态，且本次结果与首次提交一致。 */
    DUPLICATE_SUBMISSION("重复提交：该结果已被接受，请勿重复提交", HttpStatus.CONFLICT),

    // ---------- 状态机 ----------
    /** 非法状态流转。 */
    ILLEGAL_TRANSITION("非法的状态流转", HttpStatus.CONFLICT),
    /** 任务已在终态，拒绝任何修改。 */
    TERMINAL_STATE("任务已进入终态，拒绝续租或提交结果", HttpStatus.CONFLICT),
    /** 失败重试次数已用尽，任务不再派发。 */
    RETRY_EXHAUSTED("失败重试次数已用尽，任务进入最终失败", HttpStatus.CONFLICT),

    // ---------- 游标 ----------
    /** 游标格式非法或签名校验失败（被篡改）。 */
    CURSOR_INVALID("游标无效或已被篡改", HttpStatus.BAD_REQUEST),
    /** 游标超过有效期。 */
    CURSOR_EXPIRED("游标已过期，请从第一页重新开始翻页", HttpStatus.BAD_REQUEST),
    /** 游标锚点记录已不存在（被删除）。 */
    CURSOR_ANCHOR_DELETED("游标指向的记录已被删除，请从第一页重新开始翻页", HttpStatus.BAD_REQUEST);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String defaultMessage, HttpStatus httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
