package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：同一任务被多个执行者并发领取。
 * 预期：恰好 1 个成功；其余全部为 CONCURRENT_MODIFICATION（可与租约/重复提交区分）。
 */
@SpringBootTest(properties = "durable.store-file=target/test-data/concurrent-claim")
@Import(TestClockConfig.class)
class ConcurrentClaimTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentClaimTest.class);

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
    void concurrentClaim_onlyOneWinner_othersGetConcurrentModification() throws Exception {
        JobTask task = taskService.create("concurrent-job");
        int workers = 16;
        log.info("【输入】taskId={}, 并发执行者数={}", task.getId(), workers);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            final String workerId = "worker-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    JobTask claimed = taskService.claim(workerId, task.getId());
                    success.incrementAndGet();
                    log.info("【领取成功】worker={}, leaseToken={}", workerId, claimed.getLeaseToken());
                } catch (ApiException e) {
                    if (e.getCode() == ErrorCode.CONCURRENT_MODIFICATION) {
                        conflict.incrementAndGet();
                        log.info("【并发冲突】worker={}, errorCode={}, 判定依据=状态已为RUNNING且持有者不是自己",
                                workerId, e.getCode());
                    } else {
                        other.incrementAndGet();
                        log.error("【非预期错误码】worker={}, code={}", workerId, e.getCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        log.info("【结果】成功={}, CONCURRENT_MODIFICATION={}, 其他={}", success.get(), conflict.get(), other.get());
        assertEquals(1, success.get(), "必须恰好一个执行者领取成功，杜绝重复执行");
        assertEquals(workers - 1, conflict.get(), "其余请求必须是可区分的并发修改冲突");
        assertEquals(0, other.get(), "不允许出现未分类错误");

        JobTask stored = taskService.get(task.getId());
        assertEquals(1, stored.getAttemptCount(), "失败的并发领取不得累加尝试次数");
        log.info("【判定】任务 attemptCount={}, owner={}", stored.getAttemptCount(), stored.getWorkerId());
    }
}
