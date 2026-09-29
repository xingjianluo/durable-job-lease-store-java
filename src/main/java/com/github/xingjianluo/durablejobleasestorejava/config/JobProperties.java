package com.github.xingjianluo.durablejobleasestorejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可调参数（application.properties 前缀 job）：
 * 租约时长、回收扫描间隔、失败重试上限与退避、游标 TTL、持久化目录。
 */
@ConfigurationProperties(prefix = "job")
public class JobProperties {

    /** 租约时长（毫秒）。 */
    private long leaseTtlMillis = 30_000;
    /** 回收器扫描间隔（毫秒）。 */
    private long reaperIntervalMillis = 1_000;
    /** 失败重试最大次数（不含首次执行）。 */
    private int maxAttempts = 3;
    /** 第 n 次重试的基础退避：baseBackoffMillis * 2^(n-1)。 */
    private long baseBackoffMillis = 2_000;
    /** 退避上限（毫秒）。 */
    private long maxBackoffMillis = 60_000;
    /** 游标有效期（毫秒）。 */
    private long cursorTtlMillis = 300_000;
    /** 游标签名密钥。 */
    private String cursorSecret = "durable-job-local-dev-secret";
    /** 本地持久化目录，空表示不持久化（纯内存）。 */
    private String dataDir = "data/jobstore";

    public long getLeaseTtlMillis() { return leaseTtlMillis; }
    public void setLeaseTtlMillis(long leaseTtlMillis) { this.leaseTtlMillis = leaseTtlMillis; }
    public long getReaperIntervalMillis() { return reaperIntervalMillis; }
    public void setReaperIntervalMillis(long reaperIntervalMillis) { this.reaperIntervalMillis = reaperIntervalMillis; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getBaseBackoffMillis() { return baseBackoffMillis; }
    public void setBaseBackoffMillis(long baseBackoffMillis) { this.baseBackoffMillis = baseBackoffMillis; }
    public long getMaxBackoffMillis() { return maxBackoffMillis; }
    public void setMaxBackoffMillis(long maxBackoffMillis) { this.maxBackoffMillis = maxBackoffMillis; }
    public long getCursorTtlMillis() { return cursorTtlMillis; }
    public void setCursorTtlMillis(long cursorTtlMillis) { this.cursorTtlMillis = cursorTtlMillis; }
    public String getCursorSecret() { return cursorSecret; }
    public void setCursorSecret(String cursorSecret) { this.cursorSecret = cursorSecret; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
}
