package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.domain.TaskStatus;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.service.TaskService;
import com.github.xingjianluo.durablejobleasestorejava.store.TaskStore;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.MutableClock;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：执行者重复提交完成结果、租约过期后提交、被他人接管后提交、终态后提交。
 * 四类拒绝必须返回不同的 errorCode，调用方可据此分支处理。
 */
@SpringBootTest(properties = "durable.store-file=target/test-data/duplicate-submit")
@Import(TestClockConfig.class)
class DuplicateSubmissionTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateSubmissionTest.class);

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private MutableClock clock;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        taskStore.clear();
        clock.set(com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig.FIXED_START);
    }

    @Test
    void duplicateCompletion_isDetectedAndDistinguishable() {
        JobTask task = taskService.create("dup-job");
        JobTask claimed = taskService.claim("worker-A", task.getId());
        log.info("【输入】taskId={}, worker=A, token={}", task.getId(), claimed.getLeaseToken());

        JobTask succeeded = taskService.complete(task.getId(), "worker-A", claimed.getLeaseToken(), true, null);
        assertEquals(TaskStatus.SUCCEEDED, succeeded.getStatus());
        log.info("【首次提交】succeeded=true 已接受, status={}", succeeded.getStatus());

        // 1) 同一执行者同一令牌再次提交成功 -> DUPLICATE_SUBMISSION
        ApiException dup = assertThrows(ApiException.class, () ->
                taskService.complete(task.getId(), "worker-A", claimed.getLeaseToken(), true, null));
        log.info("【再次相同提交】errorCode={}, message={}", dup.getCode(), dup.getMessage());
        assertEquals(ErrorCode.DUPLICATE_SUBMISSION, dup.getCode());

        // 2) 终态后用“不同结果”提交 -> TERMINAL_STATE（不是幂等重复）
        ApiException terminal = assertThrows(ApiException.class, () ->
                taskService.complete(task.getId(), "worker-A", claimed.getLeaseToken(), false, "now fail"));
        log.info("【终态后提交不同结果】errorCode={}", terminal.getCode());
        assertEquals(ErrorCode.TERMINAL_STATE, terminal.getCode());

        // 3) 终态后续租 -> TERMINAL_STATE
        ApiException renewTerminal = assertThrows(ApiException.class, () ->
                taskService.renew(task.getId(), "worker-A", claimed.getLeaseToken()));
        log.info("【终态后续租】errorCode={}", renewTerminal.getCode());
        assertEquals(ErrorCode.TERMINAL_STATE, renewTerminal.getCode());

        // 状态保持终态，未被覆盖
        assertEquals(TaskStatus.SUCCEEDED, taskService.get(task.getId()).getStatus());
        log.info("【判定】最终 status=SUCCEEDED，重复请求未覆盖状态");
    }

    @Test
    void expiredAndStolenLease_areRejectedWithDistinctCodes() {
        JobTask task = taskService.create("lease-job");
        JobTask claimed = taskService.claim("worker-A", task.getId());
        log.info("【输入】taskId={}, A 持有租约 ttl=10s, token={}", task.getId(), claimed.getLeaseToken());

        // 时钟走过租约期
        clock.advance(Duration.ofSeconds(11));
        log.info("【推进时钟】+11s，租约已过期 expiredAt={}", claimed.getLeaseExpiresAt());

        ApiException expiredComplete = assertThrows(ApiException.class, () ->
                taskService.complete(task.getId(), "worker-A", claimed.getLeaseToken(), true, null));
        log.info("【过期后提交】errorCode={}", expiredComplete.getCode());
        assertEquals(ErrorCode.LEASE_EXPIRED, expiredComplete.getCode());

        ApiException expiredRenew = assertThrows(ApiException.class, () ->
                taskService.renew(task.getId(), "worker-A", claimed.getLeaseToken()));
        log.info("【过期后续租】errorCode={}", expiredRenew.getCode());
        assertEquals(ErrorCode.LEASE_EXPIRED, expiredRenew.getCode());

        // 系统回收 -> 重新派发 -> worker-B 接管
        int reaped = taskService.reapExpiredLeases();
        log.info("【回收】受影响任务数={}", reaped);
        assertEquals(1, reaped);

        // 退避后（测试配置 backoff-base=2s）再被 B 领取
        clock.advance(Duration.ofSeconds(3));
        JobTask reclaimed = taskService.claim("worker-B", task.getId());
        log.info("【接管】worker=B, 新 token={}, attempt={}", reclaimed.getLeaseToken(), reclaimed.getAttemptCount());
        assertNotEquals(claimed.getLeaseToken(), reclaimed.getLeaseToken());

        // A 用旧令牌续租 -> LEASE_CONFLICT（被接管）
        ApiException stolen = assertThrows(ApiException.class, () ->
                taskService.renew(task.getId(), "worker-A", claimed.getLeaseToken()));
        log.info("【旧持有者续租】errorCode={}, 判定依据=令牌与当前持有者不匹配", stolen.getCode());
        assertEquals(ErrorCode.LEASE_CONFLICT, stolen.getCode());

        // A 用旧令牌提交 -> LEASE_CONFLICT
        ApiException stolenComplete = assertThrows(ApiException.class, () ->
                taskService.complete(task.getId(), "worker-A", claimed.getLeaseToken(), true, null));
        assertEquals(ErrorCode.LEASE_CONFLICT, stolenComplete.getCode());
        log.info("【旧持有者提交】errorCode={}", stolenComplete.getCode());

        // 任意编造令牌 -> LEASE_CONFLICT
        ApiException bogus = assertThrows(ApiException.class, () ->
                taskService.complete(task.getId(), "worker-B", "forged-token", true, null));
        assertEquals(ErrorCode.LEASE_CONFLICT, bogus.getCode());
        log.info("【伪造令牌提交】errorCode={}", bogus.getCode());

        assertEquals(TaskStatus.RUNNING, taskService.get(task.getId()).getStatus());
        log.info("【判定】任务仍为 RUNNING，owner=worker-B，旧持有者与伪造请求均未改变状态");
    }
}
