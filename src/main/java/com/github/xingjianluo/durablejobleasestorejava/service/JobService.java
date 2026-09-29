package com.github.xingjianluo.durablejobleasestorejava.service;

import com.github.xingjianluo.durablejobleasestorejava.domain.CompletionRecord;
import com.github.xingjianluo.durablejobleasestorejava.domain.Job;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.lease.LeaseService;
import com.github.xingjianluo.durablejobleasestorejava.store.JobStore;
import com.github.xingjianluo.durablejobleasestorejava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 应用服务：在存储原子操作之上编排状态机、租约身份校验与完成幂等。
 *
 * <p>三类冲突明确区分：</p>
 * <ul>
 *   <li>{@link ErrorCode#VERSION_CONFLICT}：版本号不一致（并发修改 / 静默丢更新）；</li>
 *   <li>{@link ErrorCode#LEASE_EXPIRED} / {@link ErrorCode#LEASE_TAKEN_OVER}：租约过期或已易主；</li>
 *   <li>{@link ErrorCode#ALREADY_COMPLETED}：终态后重复提交完成结果（幂等返回首次结果）。</li>
 * </ul>
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobStore store;
    private final LeaseService leases;
    private final TimeSource time;

    public JobService(JobStore store, LeaseService leases, TimeSource time) {
        this.store = store;
        this.leases = leases;
        this.time = time;
    }

    public JobView createJob(String type, String payload) {
        if (type == null || type.isBlank()) {
            throw new JobApiException(ErrorCode.VALIDATION_ERROR, "type 不能为空");
        }
        return JobView.of(store.create(type, payload));
    }

    public JobView getJob(String id) {
        return JobView.of(load(id));
    }

    public JobStore.Page listJobs(int limit, String cursor) {
        if (limit <= 0 || limit > 200) {
            throw new JobApiException(ErrorCode.VALIDATION_ERROR, "limit 必须在 1..200 之间");
        }
        return store.list(limit, cursor);
    }

    /** 领取结果：成功时携带新租约；任务全部不可派发时 job 为 null。 */
    public record ClaimResult(JobView job, String leaseToken, boolean claimed) {
    }

    /**
     * 原子地领取下一个可派发任务：读-校验-条件 CAS 全在 store 锁内完成，
     * 多执行者并发领取同一任务时只有一个成功，其余得到 VERSION_CONFLICT/LEASE_CONFLICT。
     */
    public ClaimResult claimNext(String workerId) {
        long now = time.nowMillis();
        for (int guard = 0; guard < 32; guard++) {
            Job candidate = store.nextDispatchable(now).orElse(null);
            if (candidate == null) {
                return new ClaimResult(null, null, false);
            }
            String token = leases.newToken();
            long expiresAt = leases.expiresAt(now);
            try {
                Job claimed = store.claimIfWaiting(candidate.getId(), candidate.getVersion(),
                        workerId, token, expiresAt, now);
                log.info("claim 成功 worker={} id={} version={} expiresAt={}",
                        workerId, claimed.getId(), claimed.getVersion(), expiresAt);
                return new ClaimResult(JobView.of(claimed), token, true);
            } catch (JobApiException e) {
                if (e.code() == ErrorCode.VERSION_CONFLICT || e.code() == ErrorCode.LEASE_CONFLICT) {
                    // 被并发执行者抢走：尝试下一个可派发任务
                    log.info("claim 冲突 worker={} id={} code={}，尝试下一个", workerId, candidate.getId(), e.code());
                    continue;
                }
                throw e;
            }
        }
        return new ClaimResult(null, null, false);
    }

    public record CompleteResult(JobView job, boolean duplicate) {
    }

    /**
     * 提交完成结果。重复提交（任务已终态且 completion 已存在）：
     * 返回首次结果并标 duplicate=true，状态绝不被覆盖；租约/状态不合法则给明确错误码。
     */
    public CompleteResult complete(String id, String leaseToken, boolean succeeded, String result) {
        long now = time.nowMillis();
        Job job = load(id);

        if (job.getStatus().isTerminal()) {
            if (job.getCompletion() != null) {
                log.info("complete 幂等命中 id={} 首次结果 succeeded={}", id, job.getCompletion().succeeded());
                return new CompleteResult(JobView.of(job), true);
            }
            // CANCELED 终态且无完成记录
            throw new JobApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "任务已处于终态 " + job.getStatus() + "，不能提交完成结果");
        }

        checkActiveLease(job, leaseToken, "complete");

        final CompletionRecord record = new CompletionRecord(succeeded, result, job.getLeaseHolder(), now);
        Job updated;
        if (succeeded) {
            updated = store.compareAndUpdate(job, m -> {
                m.setStatus(JobStatus.SUCCEEDED);
                m.setCompletion(record);
                clearLease(m);
            }, now);
        } else {
            // 执行方主动上报失败：进入与超时回收一致的重试判定
            int attempts = job.getAttempts();
            if (attempts >= leases.maxAttempts()) {
                updated = store.compareAndUpdate(job, m -> {
                    m.setStatus(JobStatus.FAILED);
                    m.setCompletion(record);
                    clearLease(m);
                }, now);
                log.info("complete(failure) 达到重试上限 id={} attempts={}/{}",
                        id, attempts, leases.maxAttempts());
            } else {
                long notBefore = leases.nextDispatchTime(now, attempts);
                updated = store.compareAndUpdate(job, m -> {
                    m.setStatus(JobStatus.WAITING);
                    m.setCompletion(record); // 保留最近一次失败回执
                    m.setNotBeforeMillis(notBefore);
                    clearLease(m);
                }, now);
                log.info("complete(failure) 进入退避重派 id={} attempts={} notBefore={}",
                        id, attempts, notBefore);
            }
        }
        return new CompleteResult(JobView.of(updated), false);
    }

    /** 续租：租约必须有效且 token 匹配；过期/易主/终态一律拒绝。 */
    public JobView renew(String id, String leaseToken) {
        long now = time.nowMillis();
        Job job = load(id);
        if (job.getStatus().isTerminal()) {
            throw new JobApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "任务已处于终态 " + job.getStatus() + "，不能续租");
        }
        checkActiveLease(job, leaseToken, "renew");
        long expiresAt = leases.expiresAt(now);
        Job updated = store.compareAndUpdate(job, m -> m.setLeaseExpiresAtMillis(expiresAt), now);
        log.info("renew 成功 id={} worker={} newExpiresAt={}", id, job.getLeaseHolder(), expiresAt);
        return JobView.of(updated);
    }

    /** 取消：仅待领取可取消（执行中需持有者通过完成/放弃流程）。 */
    public JobView cancel(String id) {
        long now = time.nowMillis();
        Job job = load(id);
        if (job.getStatus() == JobStatus.CANCELED) {
            return JobView.of(job);
        }
        if (job.getStatus() != JobStatus.WAITING) {
            throw new JobApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "只有待领取(WAITING)任务可取消，当前 status=" + job.getStatus());
        }
        Job updated = store.compareAndUpdate(job, m -> {
            m.setStatus(JobStatus.CANCELED);
            clearLease(m);
        }, now);
        log.info("cancel 成功 id={}", id);
        return JobView.of(updated);
    }

    public void deleteTerminal(String id) {
        if (!deleteTerminalAndReturn(id)) {
            throw new JobApiException(ErrorCode.NOT_FOUND, "任务不存在或已被删除: " + id);
        }
    }

    public boolean deleteTerminalAndReturn(String id) {
        return store.deleteTerminal(id);
    }

    /**
     * 校验调用方持有的是当前有效租约，区分三种拒绝原因：
     * LEASE_EXPIRED（过期）/ LEASE_TAKEN_OVER（token 不匹配，已易主）/ ILLEGAL_TRANSITION（非执行中）。
     */
    private void checkActiveLease(Job job, String leaseToken, String action) {
        if (job.getStatus() != JobStatus.RUNNING) {
            throw new JobApiException(ErrorCode.ILLEGAL_TRANSITION,
                    action + " 要求任务处于执行中(RUNNING)，当前 status=" + job.getStatus());
        }
        if (job.getLeaseExpiresAtMillis() <= time.nowMillis()) {
            throw new JobApiException(ErrorCode.LEASE_EXPIRED,
                    action + " 被拒绝：租约已于 " + job.getLeaseExpiresAtMillis() + " 过期");
        }
        if (!Objects.equals(job.getLeaseToken(), leaseToken)) {
            throw new JobApiException(ErrorCode.LEASE_TAKEN_OVER,
                    action + " 被拒绝：租约 token 不匹配，任务已被其他执行者接管，leaseHolder="
                            + job.getLeaseHolder());
        }
    }

    private static void clearLease(Job m) {
        m.setLeaseToken(null);
        m.setLeaseHolder(null);
        m.setLeaseExpiresAtMillis(0L);
    }

    private Job load(String id) {
        return store.get(id).orElseThrow(() -> new JobApiException(ErrorCode.NOT_FOUND, "任务不存在: " + id));
    }
}
