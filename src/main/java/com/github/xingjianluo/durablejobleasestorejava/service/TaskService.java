package com.github.xingjianluo.durablejobleasestorejava.service;

import com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties;
import com.github.xingjianluo.durablejobleasestorejava.cursor.Cursor;
import com.github.xingjianluo.durablejobleasestorejava.cursor.CursorCodec;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.domain.TaskStatus;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 任务领域服务。所有状态变更通过 store.mutate 在存储层锁内完成“校验 + 写入”，
 * 因此并发领取、重复完成、租约接管等都不会互相覆盖或静默丢更新。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskStore store;
    private final CursorCodec cursorCodec;
    private final LeaseProperties properties;
    private final Clock clock;

    public TaskService(TaskStore store, CursorCodec cursorCodec,
                       LeaseProperties properties, Clock clock) {
        this.store = store;
        this.cursorCodec = cursorCodec;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 创建

    public JobTask create(String payload) {
        Instant now = Instant.now(clock);
        JobTask task = new JobTask();
        task.setId(newId(now));
        task.setPayload(payload == null ? "" : payload);
        task.setStatus(TaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setTimeoutCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        store.insert(task);
        log.info("创建任务 id={}, payloadLen={}", task.getId(), task.getPayload().length());
        return task;
    }

    public JobTask get(String id) {
        return store.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + id));
    }

    // ------------------------------------------------------------ 游标分页

    public PageResult list(String cursorToken, int limit) {
        if (limit <= 0 || limit > 200) {
            limit = 20;
        }
        String anchorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            // decode 内部已区分：被篡改 CURSOR_INVALID / 过期 CURSOR_EXPIRED
            Cursor cursor = cursorCodec.decode(cursorToken);
            anchorId = cursor.anchorId();
            if (store.findById(anchorId).isEmpty()) {
                log.info("游标锚点已不存在 anchorId={} -> CURSOR_ANCHOR_DELETED", anchorId);
                throw new ApiException(ErrorCode.CURSOR_ANCHOR_DELETED,
                        "游标锚点任务已被删除: " + anchorId);
            }
        }
        // 多取一条用于判断 hasMore
        List<JobTask> rows = store.findPage(anchorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        String nextCursor = hasMore ? cursorCodec.encode(rows.get(rows.size() - 1).getId()) : null;
        log.debug("列表查询 anchor={}, limit={}, returned={}, hasMore={}",
                anchorId, limit, rows.size(), hasMore);
        return new PageResult(List.copyOf(rows), nextCursor, hasMore);
    }

    // ---------------------------------------------------------------- 领取

    /**
     * 领取指定任务。并发领取时只有一个线程能把 PENDING 改成 RUNNING，
     * 其余得到 CONCURRENT_MODIFICATION。
     */
    public JobTask claim(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "workerId 不能为空");
        }
        return store.mutate(taskId, t -> {
            if (t.getStatus() == TaskStatus.RUNNING) {
                log.info("claim 冲突(并发领取) taskId={}, 当前持有者={}, 申请者={}",
                        taskId, t.getWorkerId(), workerId);
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                        "任务已被执行者 " + t.getWorkerId() + " 领取，租约到期后才可重新派发");
            }
            if (t.getStatus().isTerminal()) {
                throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                        "任务已终态(" + t.getStatus() + ")，不能被领取");
            }
            // PENDING：检查失败退避窗口
            Instant now = Instant.now(clock);
            if (t.getNotBefore() != null && t.getNotBefore().isAfter(now)) {
                throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                        "任务处于失败退避期，" + t.getNotBefore() + " 之后才可再次派发");
            }
            String token = newLeaseToken();
            t.setStatus(TaskStatus.RUNNING);
            t.setWorkerId(workerId);
            t.setLeaseToken(token);
            t.setLeaseExpiresAt(now.plus(properties.getLeaseTtl()));
            t.setAttemptCount(t.getAttemptCount() + 1);
            t.setNotBefore(null);
            log.info("任务被领取 taskId={}, worker={}, attempt={}, leaseExpiresAt={}, token={}",
                    taskId, workerId, t.getAttemptCount(), t.getLeaseExpiresAt(), token);
            return t;
        });
    }

    /** 领取任意一个可派发任务；没有则返回 null。 */
    public JobTask claimAny(String workerId) {
        List<JobTask> candidates = store.findDispatchable(1);
        if (candidates.isEmpty()) {
            return null;
        }
        try {
            return claim(workerId, candidates.get(0).getId());
        } catch (ApiException e) {
            // 与其它执行者竞争失败或恰好退避，视为无任务
            if (e.getCode() == ErrorCode.CONCURRENT_MODIFICATION
                    || e.getCode() == ErrorCode.ILLEGAL_TRANSITION) {
                return null;
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- 续租

    public JobTask renew(String taskId, String workerId, String leaseToken) {
        return store.mutate(taskId, t -> {
            // 终态优先级最高：任务完成/失败/取消后一律拒绝
            if (t.getStatus().isTerminal()) {
                log.info("续租被拒(终态) taskId={}, status={}, worker={}",
                        taskId, t.getStatus(), workerId);
                throw new ApiException(ErrorCode.TERMINAL_STATE,
                        "任务已进入终态 " + t.getStatus() + "，续租被拒绝");
            }
            Instant now = Instant.now(clock);
            // 租约已过期（无论是否已被重新派发）
            if (t.getLeaseExpiresAt() != null && !t.getLeaseExpiresAt().isAfter(now)) {
                log.info("续租被拒(租约过期) taskId={}, expiredAt={}, now={}, worker={}",
                        taskId, t.getLeaseExpiresAt(), now, workerId);
                throw new ApiException(ErrorCode.LEASE_EXPIRED,
                        "租约已于 " + t.getLeaseExpiresAt() + " 过期");
            }
            // 令牌/持有者不符：被他人接管，或自己根本没持有
            if (t.getStatus() != TaskStatus.RUNNING
                    || !leaseToken.equals(t.getLeaseToken())
                    || !workerId.equals(t.getWorkerId())) {
                log.info("续租被拒(租约冲突) taskId={}, expectWorker={}/expectToken={}, got={}/{}",
                        taskId, t.getWorkerId(), t.getLeaseToken(), workerId, leaseToken);
                throw new ApiException(ErrorCode.LEASE_CONFLICT,
                        "租约令牌无效或任务已被其他执行者接管");
            }
            t.setLeaseExpiresAt(now.plus(properties.getRenewTtl()));
            log.info("续租成功 taskId={}, worker={}, 新到期时间={}",
                    taskId, workerId, t.getLeaseExpiresAt());
            return t;
        });
    }

    // ---------------------------------------------------------------- 完成

    /**
     * 执行者提交结果。同一执行者用同一租约令牌重复提交 -> DUPLICATE_SUBMISSION；
     * 过期/被接管/终态分别给出 LEASE_EXPIRED / LEASE_CONFLICT / TERMINAL_STATE。
     */
    public JobTask complete(String taskId, String workerId, String leaseToken,
                            boolean succeeded, String errorMessage) {
        return store.mutate(taskId, t -> {
            // 终态：判断是否为同一结果的重复提交
            if (t.getStatus().isTerminal()) {
                boolean sameLease = leaseToken != null && leaseToken.equals(t.getLeaseToken())
                        && workerId != null && workerId.equals(t.getWorkerId());
                boolean sameOutcome = succeeded
                        ? t.getStatus() == TaskStatus.SUCCEEDED
                        : t.getStatus() == TaskStatus.FAILED;
                if (sameLease && sameOutcome) {
                    log.info("完成请求判定为重复提交 taskId={}, worker={}, status={}",
                            taskId, workerId, t.getStatus());
                    throw new ApiException(ErrorCode.DUPLICATE_SUBMISSION,
                            "任务已处于 " + t.getStatus() + "，相同结果已被接受，请勿重复提交");
                }
                log.info("完成请求被拒(终态) taskId={}, status={}, worker={}",
                        taskId, t.getStatus(), workerId);
                throw new ApiException(ErrorCode.TERMINAL_STATE,
                        "任务已终态 " + t.getStatus() + "，结果提交被拒绝");
            }
            Instant now = Instant.now(clock);
            if (t.getLeaseExpiresAt() != null && !t.getLeaseExpiresAt().isAfter(now)) {
                log.info("完成请求被拒(租约过期) taskId={}, expiredAt={}, worker={}",
                        taskId, t.getLeaseExpiresAt(), workerId);
                throw new ApiException(ErrorCode.LEASE_EXPIRED,
                        "租约已于 " + t.getLeaseExpiresAt() + " 过期，任务会被回收重派");
            }
            if (!leaseToken.equals(t.getLeaseToken()) || !workerId.equals(t.getWorkerId())) {
                log.info("完成请求被拒(租约冲突/已被接管) taskId={}, expect={}/{}, got={}/{}",
                        taskId, t.getWorkerId(), t.getLeaseToken(), workerId, leaseToken);
                throw new ApiException(ErrorCode.LEASE_CONFLICT,
                        "租约令牌无效或任务已被其他执行者接管");
            }
            if (t.getStatus() != TaskStatus.RUNNING) {
                throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                        "任务当前状态 " + t.getStatus() + "，不能提交执行结果");
            }

            if (succeeded) {
                t.setStatus(TaskStatus.SUCCEEDED);
                t.setLeaseExpiresAt(null);
                // 保留 workerId / leaseToken，作为重复提交判定依据
                log.info("任务成功 taskId={}, worker={}, attempt={}",
                        taskId, workerId, t.getAttemptCount());
                return t;
            }

            // 失败：退避重试 or 终态失败
            t.setLastError(errorMessage);
            if (t.getAttemptCount() >= properties.getMaxAttempts()) {
                t.setStatus(TaskStatus.FAILED);
                clearLease(t);
                log.warn("任务失败且重试次数用尽 taskId={}, attempts={}/{} -> FAILED ({})",
                        taskId, t.getAttemptCount(), properties.getMaxAttempts(), ErrorCode.RETRY_EXHAUSTED);
                return t;
            }
            Duration delay = backoffDelay(t.getAttemptCount());
            t.setStatus(TaskStatus.PENDING);
            t.setNotBefore(now.plus(delay));
            clearLease(t);
            log.info("任务失败，安排退避重试 taskId={}, attempts={}, 下次可派发时间=now+{}ms",
                    taskId, t.getAttemptCount(), delay.toMillis());
            return t;
        });
    }

    // ---------------------------------------------------------------- 取消

    public JobTask cancel(String taskId) {
        return store.mutate(taskId, t -> {
            if (t.getStatus() == TaskStatus.CANCELLED) {
                return null; // 已取消，幂等无变化
            }
            if (t.getStatus().isTerminal()) {
                throw new ApiException(ErrorCode.TERMINAL_STATE,
                        "任务已终态 " + t.getStatus() + "，不能取消");
            }
            if (t.getStatus() == TaskStatus.RUNNING) {
                Instant now = Instant.now(clock);
                if (t.getLeaseExpiresAt() != null && t.getLeaseExpiresAt().isAfter(now)) {
                    throw new ApiException(ErrorCode.LEASE_CONFLICT,
                            "任务正被 " + t.getWorkerId() + " 持有有效租约，不能取消；可等待租约过期");
                }
            }
            t.setStatus(TaskStatus.CANCELLED);
            clearLease(t);
            log.info("任务被取消 taskId={}", taskId);
            return t;
        });
    }

    // ------------------------------------------------------------ 租约回收

    /**
     * 回收所有过期租约：超时计入一次尝试，按指数退避重新派发，次数用尽则置 FAILED。
     * 返回回收的任务数。
     */
    public int reapExpiredLeases() {
        List<JobTask> expired = store.findExpiredLeases();
        int reaped = 0;
        Instant now = Instant.now(clock);
        for (JobTask t : expired) {
            try {
                store.mutate(t.getId(), cur -> {
                    // 双重检查：可能已被持有者在最后一刻续租/完成
                    if (cur.getStatus() != TaskStatus.RUNNING
                            || cur.getLeaseExpiresAt() == null
                            || cur.getLeaseExpiresAt().isAfter(Instant.now(clock))) {
                        return null;
                    }
                    cur.setTimeoutCount(cur.getTimeoutCount() + 1);
                    if (cur.getAttemptCount() >= properties.getMaxAttempts()) {
                        cur.setStatus(TaskStatus.FAILED);
                        cur.setLastError("lease timeout, retries exhausted");
                        clearLease(cur);
                        log.warn("租约过期且重试次数用尽 taskId={}, worker={}, timeouts={} -> FAILED",
                                cur.getId(), cur.getWorkerId(), cur.getTimeoutCount());
                    } else {
                        Duration delay = backoffDelay(cur.getAttemptCount());
                        cur.setStatus(TaskStatus.PENDING);
                        cur.setNotBefore(Instant.now(clock).plus(delay));
                        cur.setLastError("lease timeout, re-dispatch after backoff");
                        log.warn("租约过期，回收重派 taskId={}, worker={}, timeouts={}, 退避={}ms",
                                cur.getId(), cur.getWorkerId(), cur.getTimeoutCount(), delay.toMillis());
                        clearLease(cur);
                    }
                    return cur;
                });
                reaped++;
            } catch (ApiException e) {
                log.debug("回收时任务状态已变化 taskId={}, code={}", t.getId(), e.getCode());
            }
        }
        if (reaped > 0) {
            log.info("租约回收完成，本次回收 {} 个任务（扫描候选 {}）", reaped, expired.size());
        }
        return reaped;
    }

    public void delete(String id) {
        if (!store.deleteById(id)) {
            throw new ApiException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + id);
        }
    }

    // ------------------------------------------------------------ 内部工具

    private void clearLease(JobTask t) {
        t.setWorkerId(null);
        t.setLeaseToken(null);
        t.setLeaseExpiresAt(null);
    }

    /** 指数退避：base * multiplier^(attempt-1)，上限 backoffMax。 */
    public Duration backoffDelay(int attempt) {
        double factor = Math.pow(properties.getBackoffMultiplier(), Math.max(0, attempt - 1));
        long millis = (long) (properties.getBackoffBase().toMillis() * factor);
        long capped = Math.min(millis, properties.getBackoffMax().toMillis());
        return Duration.ofMillis(capped);
    }

    /** 时间有序 ID：毫秒时间戳十六进制 + 128 位随机后缀，同毫秒内仍唯一，整体按时间近似有序。 */
    static String newId(Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return Long.toHexString(now.toEpochMilli()) + "-" + suffix;
    }

    static String newLeaseToken() {
        return UUID.randomUUID().toString();
    }
}
