package com.github.xingjianluo.durablejobleasestorejava.domain;

import java.time.Instant;

/**
 * 任务实体（持久化快照的一部分）。
 */
public class JobTask {

    /** 任务 ID，全局唯一（单调 ULID，同时作为游标分页的排序键之一）。 */
    private String id;

    /** 任务载荷，创建时由调用方提供。 */
    private String payload;

    private TaskStatus status;

    // ---------- 租约相关 ----------
    /** 当前持有者（执行者）标识；PENDING / 终态时为 null。 */
    private String workerId;
    /** 本次租约令牌（领取时生成），续租/完成必须携带，用于区分接管前后的旧持有者。 */
    private String leaseToken;
    /** 租约到期时间（绝对时间）；为 null 表示当前无有效租约。 */
    private Instant leaseExpiresAt;
    /** 本任务租约被过期回收的次数（日志/排查用）。 */
    private int timeoutCount;

    // ---------- 失败重试 ----------
    /** 已执行（尝试）次数：每次进入 RUNNING 累加。 */
    private int attemptCount;
    /** 下次允许被领取（派发）的时间，失败退避期间晚于当前时间。 */
    private Instant notBefore;
    /** 最近一次失败原因。 */
    private String lastError;

    // ---------- 时间戳 ----------
    private Instant createdAt;
    private Instant updatedAt;

    public JobTask() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public int getTimeoutCount() { return timeoutCount; }
    public void setTimeoutCount(int timeoutCount) { this.timeoutCount = timeoutCount; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getNotBefore() { return notBefore; }
    public void setNotBefore(Instant notBefore) { this.notBefore = notBefore; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
