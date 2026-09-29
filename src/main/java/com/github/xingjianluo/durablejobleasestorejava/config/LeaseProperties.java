package com.github.xingjianluo.durablejobleasestorejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 租约 / 重试 / 游标相关可调参数（前缀 durable）。
 */
@ConfigurationProperties(prefix = "durable")
public class LeaseProperties {

    /** 单次领取租约时长。 */
    private Duration leaseTtl = Duration.ofSeconds(30);

    /** 续租时长。 */
    private Duration renewTtl = Duration.ofSeconds(30);

    /** 失败后最大重试次数（不含首次执行）。 */
    private int maxAttempts = 3;

    /** 首次失败退避，之后指数递增：base * 2^(attempt-1)，并受 backoffMax 限制。 */
    private Duration backoffBase = Duration.ofSeconds(2);
    private Duration backoffMax = Duration.ofSeconds(60);
    private double backoffMultiplier = 2.0;

    /** 租约回收扫描周期。 */
    private Duration reaperInterval = Duration.ofSeconds(5);

    /** 游标有效期。 */
    private Duration cursorTtl = Duration.ofMinutes(10);

    /** 游标签名密钥（本地演示用固定值，生产应注入）。 */
    private String cursorSecret = "durable-job-local-dev-secret";

    /** 持久化快照文件位置。 */
    private String storeFile = "data/task-store.json";

    public Duration getLeaseTtl() { return leaseTtl; }
    public void setLeaseTtl(Duration leaseTtl) { this.leaseTtl = leaseTtl; }

    public Duration getRenewTtl() { return renewTtl; }
    public void setRenewTtl(Duration renewTtl) { this.renewTtl = renewTtl; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Duration getBackoffBase() { return backoffBase; }
    public void setBackoffBase(Duration backoffBase) { this.backoffBase = backoffBase; }

    public Duration getBackoffMax() { return backoffMax; }
    public void setBackoffMax(Duration backoffMax) { this.backoffMax = backoffMax; }

    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }

    public Duration getReaperInterval() { return reaperInterval; }
    public void setReaperInterval(Duration reaperInterval) { this.reaperInterval = reaperInterval; }

    public Duration getCursorTtl() { return cursorTtl; }
    public void setCursorTtl(Duration cursorTtl) { this.cursorTtl = cursorTtl; }

    public String getCursorSecret() { return cursorSecret; }
    public void setCursorSecret(String cursorSecret) { this.cursorSecret = cursorSecret; }

    public String getStoreFile() { return storeFile; }
    public void setStoreFile(String storeFile) { this.storeFile = storeFile; }
}
