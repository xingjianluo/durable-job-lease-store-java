package com.github.xingjianluo.durablejobleasestorejava.lease;

import com.github.xingjianluo.durablejobleasestorejava.domain.CompletionRecord;
import com.github.xingjianluo.durablejobleasestorejava.domain.Job;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.store.JobStore;
import com.github.xingjianluo.durablejobleasestorejava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租约回收器：周期扫描执行中且租约已过期的任务。
 * <ul>
 *   <li>attempts &lt; maxAttempts：回到 WAITING，notBefore = now + 指数退避，供重新派发；</li>
 *   <li>attempts &gt;= maxAttempts：置 FAILED 终态（记录“租约超时”），不再无限重派。</li>
 * </ul>
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobStore store;
    private final LeaseService leases;
    private final TimeSource time;
    private final AtomicInteger reapedCount = new AtomicInteger();

    public LeaseReaper(JobStore store, LeaseService leases, TimeSource time) {
        this.store = store;
        this.leases = leases;
        this.time = time;
    }

    public int reapedCount() {
        return reapedCount.get();
    }

    @Scheduled(fixedDelayString = "${job.reaper-interval-millis:1000}")
    public void tick() {
        reapExpiredOnce();
    }

    /** 执行一轮回收（也供测试直接调用）。返回本轮回收的任务数。 */
    public int reapExpiredOnce() {
        long now = time.nowMillis();
        List<Job> expired = store.allRunningSnapshot().stream()
                .filter(j -> j.getLeaseExpiresAtMillis() > 0 && j.getLeaseExpiresAtMillis() <= now)
                .toList();
        int n = 0;
        for (Job job : expired) {
            try {
                reapOne(job, now);
                n++;
            } catch (JobApiException e) {
                // VERSION_CONFLICT：任务在扫描后刚被完成/续租，跳过即可
                if (e.code() != ErrorCode.VERSION_CONFLICT) {
                    log.warn("回收任务 {} 失败 code={} msg={}", job.getId(), e.code(), e.getMessage());
                }
            }
        }
        if (n > 0) {
            reapedCount.addAndGet(n);
            log.info("reaper 本轮回收 {} 个过期租约任务（累计 {}）", n, reapedCount.get());
        }
        return n;
    }

    private void reapOne(Job job, long now) {
        int attempts = job.getAttempts();
        if (attempts >= leases.maxAttempts()) {
            CompletionRecord rec = new CompletionRecord(false,
                    "租约超时且重试次数已达上限 attempts=" + attempts, job.getLeaseHolder(), now);
            store.compareAndUpdate(job, m -> {
                m.setStatus(JobStatus.FAILED);
                m.setCompletion(rec);
                m.setLeaseToken(null);
                m.setLeaseHolder(null);
                m.setLeaseExpiresAtMillis(0L);
            }, now);
            log.info("reaper 置失败 id={} attempts={}/{} holder={}",
                    job.getId(), attempts, leases.maxAttempts(), job.getLeaseHolder());
        } else {
            long notBefore = leases.nextDispatchTime(now, attempts);
            store.compareAndUpdate(job, m -> {
                m.setStatus(JobStatus.WAITING);
                m.setNotBeforeMillis(notBefore);
                m.setLeaseToken(null);
                m.setLeaseHolder(null);
                m.setLeaseExpiresAtMillis(0L);
            }, now);
            log.info("reaper 回收重派 id={} attempts={} notBefore={}（退避等待）",
                    job.getId(), attempts, notBefore);
        }
    }
}
