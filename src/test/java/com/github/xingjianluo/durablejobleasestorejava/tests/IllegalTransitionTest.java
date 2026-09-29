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
 * 场景：不合法的状态流转必须被拒绝。
 * 合法流转：PENDING->RUNNING；RUNNING->SUCCEEDED / PENDING(可重试失败) / FAILED(用尽) /
 *          PENDING(租约回收) / CANCELLED(租约过期后)；PENDING->CANCELLED。
 */
@SpringBootTest(properties = "durable.store-file=target/test-data/illegal-transition")
@Import(TestClockConfig.class)
class IllegalTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(IllegalTransitionTest.class);

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private MutableClock clock;

    private ApiException expectCode(ErrorCode expected, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getCode(), "期望错误码 " + expected + " 实际 " + ex.getCode());
        return ex;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        taskStore.clear();
        clock.set(com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig.FIXED_START);
    }

    @Test
    void illegalTransitions_areRejected_legalOnesAccepted() {
        // PENDING 任务不能直接 complete / renew
        JobTask t1 = taskService.create("t1");
        log.info("【输入】PENDING 任务 id={}，尝试直接续租/提交", t1.getId());
        ApiException e1 = expectCode(ErrorCode.LEASE_CONFLICT,
                () -> taskService.renew(t1.getId(), "w", "any-token"));
        log.info("【PENDING 续租】code={}, message={}", e1.getCode(), e1.getMessage());
        ApiException e2 = expectCode(ErrorCode.LEASE_CONFLICT,
                () -> taskService.complete(t1.getId(), "w", "any-token", true, null));
        log.info("【PENDING 提交结果】code={}", e2.getCode());

        // PENDING -> CANCELLED 合法；再次 cancel 幂等无变化
        JobTask cancelled = taskService.cancel(t1.getId());
        assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
        log.info("【合法】PENDING->CANCELLED");
        taskService.cancel(t1.getId());
        assertEquals(TaskStatus.CANCELLED, taskService.get(t1.getId()).getStatus());
        log.info("【幂等】重复 cancel 状态不变");

        // RUNNING 期间不能 cancel（租约仍有效）；租约过期后则可取消
        JobTask t2 = taskService.create("t2");
        taskService.claim("worker-A", t2.getId());
        ApiException e4 = expectCode(ErrorCode.LEASE_CONFLICT,
                () -> taskService.cancel(t2.getId()));
        log.info("【RUNNING 且租约有效时取消】code={}, message={}", e4.getCode(), e4.getMessage());

        clock.advance(Duration.ofSeconds(11));
        JobTask cancelledRunning = taskService.cancel(t2.getId());
        assertEquals(TaskStatus.CANCELLED, cancelledRunning.getStatus());
        log.info("【合法】RUNNING 租约过期后 -> CANCELLED");

        // SUCCEEDED 终态：任何流转都被拒绝（包括 cancel）
        JobTask t3 = taskService.create("t3");
        JobTask c3 = taskService.claim("worker-A", t3.getId());
        taskService.complete(t3.getId(), "worker-A", c3.getLeaseToken(), true, null);
        ApiException e5 = expectCode(ErrorCode.TERMINAL_STATE,
                () -> taskService.cancel(t3.getId()));
        log.info("【SUCCEEDED 后取消】code={}", e5.getCode());

        // 不存在的任务
        ApiException e6 = expectCode(ErrorCode.TASK_NOT_FOUND,
                () -> taskService.get("does-not-exist"));
        log.info("【查询不存在任务】code={}", e6.getCode());
        ApiException e7 = expectCode(ErrorCode.TASK_NOT_FOUND,
                () -> taskService.claim("worker-A", "does-not-exist"));
        log.info("【领取不存在任务】code={}", e7.getCode());

        log.info("【判定】状态机仅接受合法流转，所有非法路径均有明确错误码");
    }

    @Test
    void cancelFromRunningWithExpiredLease_thenNoRedispatch() {
        JobTask t = taskService.create("t-cancel-expired");
        JobTask c = taskService.claim("worker-A", t.getId());
        clock.advance(Duration.ofSeconds(11));
        taskService.cancel(t.getId());
        // 回收器不应把 CANCELLED 任务重新派发
        int reaped = taskService.reapExpiredLeases();
        assertEquals(0, reaped);
        assertEquals(TaskStatus.CANCELLED, taskService.get(t.getId()).getStatus());
        log.info("【判定】过期租约任务取消后不被回收器重派, reaped=0");
    }
}
