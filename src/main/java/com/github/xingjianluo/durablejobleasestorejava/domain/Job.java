package com.github.xingjianluo.durablejobleasestorejava.domain;

/**
 * 任务聚合。版本号 version 在每次持久化修改时 +1，用于乐观并发控制（重复/并发修改检测）。
 */
public class Job {
    private String id;
    private String type;
    private String payload;

    /** 单调逻辑序号：作为游标分页的稳定排序键第二部分（seq 全局单调，不受删除影响）。 */
    private long seq;
    private long createdAtMillis;
    private long updatedAtMillis;
    private long version;

    private JobStatus status;

    private int attempts;
    /** 进入 WAITING 的最早可派发时间（退避后才可被领取）。 */
    private long notBeforeMillis;

    private String leaseToken;
    private String leaseHolder;
    private long leaseExpiresAtMillis;

    private CompletionRecord completion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public void setUpdatedAtMillis(long updatedAtMillis) { this.updatedAtMillis = updatedAtMillis; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getNotBeforeMillis() { return notBeforeMillis; }
    public void setNotBeforeMillis(long notBeforeMillis) { this.notBeforeMillis = notBeforeMillis; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public String getLeaseHolder() { return leaseHolder; }
    public void setLeaseHolder(String leaseHolder) { this.leaseHolder = leaseHolder; }
    public long getLeaseExpiresAtMillis() { return leaseExpiresAtMillis; }
    public void setLeaseExpiresAtMillis(long leaseExpiresAtMillis) { this.leaseExpiresAtMillis = leaseExpiresAtMillis; }
    public CompletionRecord getCompletion() { return completion; }
    public void setCompletion(CompletionRecord completion) { this.completion = completion; }
}
