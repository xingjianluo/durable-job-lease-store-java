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
 * 场景：租约超时自动回收；失败退避逐步拉长；超过最大尝试次数后不再派发。
 * 测试配置：leaseTtl=10s, maxAttempts=3, backoffBase=2s, multiplier=2, backoffMax=30s。
 */
@SpringBootTest(properties = "durable.store-file=target/test-data/reaper")
@Import(TestClockConfig.class)
class LeaseReaperTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperTest.class);

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
    void expiredLease_isReclaimedWithBackoff_andRetriesAreCapped() {
        JobTask task = taskService.create("timeout-job");
        log.info("【输入】taskId={}, maxAttempts=3, backoff=2s,4s,8s...(上限30s)", task.getId());

        // attempt 1：领取后超时
        taskService.claim("worker-1", task.getId());
        clock.advance(Duration.ofSeconds(11));
        int reaped = taskService.reapExpiredLeases();
        JobTask after1 = taskService.get(task.getId());
        log.info("【第1次超时回收】reaped={}, status={}, notBefore={}", reaped, after1.getStatus(), after1.getNotBefore());
        assertEquals(1, reaped);
        assertEquals(TaskStatus.PENDING, after1.getStatus());
        assertEquals(1, after1.getTimeoutCount());

        // 退避期内不可领取
        clock.advance(Duration.ofSeconds(1));
        ApiException early = assertThrows(ApiException.class,
                () -> taskService.claim("worker-2", task.getId()));
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, early.getCode());
        log.info("【退避窗口内领取被拒】errorCode={}（还差 ~1s 到 notBefore）", early.getCode());

        // 退避结束后重新派发
        clock.advance(Duration.ofSeconds(2));
        JobTask attempt2 = taskService.claim("worker-2", task.getId());
        assertEquals(2, attempt2.getAttemptCount());
        log.info("【重新派发】attempt={}, worker=2", attempt2.getAttemptCount());

        // attempt 2：执行者主动报失败 -> 同样进入退避重试（4s）
        taskService.complete(task.getId(), "worker-2", attempt2.getLeaseToken(), false, "boom");
        JobTask afterFail = taskService.get(task.getId());
        assertEquals(TaskStatus.PENDING, afterFail.getStatus());
        log.info("【第2次失败】status=PENDING, lastError={}, notBefore 相对 now +4s", afterFail.getLastError());

        clock.advance(Duration.ofSeconds(5));
        JobTask attempt3 = taskService.claim("worker-3", task.getId());
        assertEquals(3, attempt3.getAttemptCount());
        log.info("【第3次派发】attempt={}", attempt3.getAttemptCount());

        // attempt 3：再次超时 -> 次数用尽 -> FAILED（终态），不再派发
        clock.advance(Duration.ofSeconds(11));
        taskService.reapExpiredLeases();
        JobTask terminal = taskService.get(task.getId());
        assertEquals(TaskStatus.FAILED, terminal.getStatus());
        assertEquals(3, terminal.getAttemptCount());
        log.info("【第3次超时】status=FAILED, attempts={}/3, 不再派发", terminal.getAttemptCount());

        // 再回收也没有候选
        int reapedAgain = taskService.reapExpiredLeases();
        assertEquals(0, reapedAgain);

        // 无法再领取终态任务
        ApiException terminalClaim = assertThrows(ApiException.class,
                () -> taskService.claim("worker-4", task.getId()));
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, terminalClaim.getCode());
        log.info("【终态任务领取被拒】errorCode={}，失败任务不会被无限重派", terminalClaim.getCode());

        // 退避间隔断言：2s / 4s 逐级拉长
        assertEquals(Duration.ofSeconds(2), taskService.backoffDelay(1));
        assertEquals(Duration.ofSeconds(4), taskService.backoffDelay(2));
        assertEquals(Duration.ofSeconds(8), taskService.backoffDelay(3));
        log.info("【判定】退避序列 2s->4s->8s 单调递增并受上限 30s 截断，重试上限=3 生效");
    }

    @Test
    void renewedLease_isNotReaped() {
        JobTask task = taskService.create("renew-job");
        JobTask claimed = taskService.claim("worker-A", task.getId());
        clock.advance(Duration.ofSeconds(9));
        JobTask renewed = taskService.renew(task.getId(), "worker-A", claimed.getLeaseToken());
        log.info("【续租】新到期时间={}", renewed.getLeaseExpiresAt());

        // 原 10s 时刻（现已被续租覆盖）不应回收
        clock.advance(Duration.ofSeconds(2));
        int reaped = taskService.reapExpiredLeases();
        assertEquals(0, reaped);
        assertEquals(TaskStatus.RUNNING, taskService.get(task.getId()).getStatus());
        log.info("【判定】续租后 +11s 仍 RUNNING，reaped=0");

        // 续租窗口也过去后才回收
        clock.advance(Duration.ofSeconds(9));
        int reapedLater = taskService.reapExpiredLeases();
        assertEquals(1, reapedLater);
        assertEquals(TaskStatus.PENDING, taskService.get(task.getId()).getStatus());
        log.info("【判定】续租窗口过后 reaped=1，status=PENDING");
    }
}
