package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发领取、租约身份冲突、重复提交结果幂等等场景。
 */
class ConcurrencyLeaseTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLeaseTest.class);

    @Test
    void onlyOneWorkerClaimsSameJobConcurrently() throws Exception {
        try (TestSupport t = new TestSupport()) {
            // 只放一个任务，多个 worker 同时抢：恰有一个成功
            t.service.createJob("only", "payload");
            int workers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<JobService.ClaimResult> successes = new ConcurrentLinkedQueue<>();
            AtomicInteger emptyClaims = new AtomicInteger();
            for (int i = 0; i < workers; i++) {
                final String worker = "w" + i;
                pool.submit(() -> {
                    start.await();
                    JobService.ClaimResult r = t.service.claimNext(worker);
                    if (r.claimed()) {
                        successes.add(r);
                    } else {
                        emptyClaims.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            log.info("输入: {} 个 worker 并发抢 1 个任务；判定: 成功领取数={}, 空手返回={}",
                    workers, successes.size(), emptyClaims.get());
            assertEquals(1, successes.size(), "同一任务只能被领取一次");
            assertEquals(workers - 1, emptyClaims.get());
            assertEquals(JobStatus.RUNNING, successes.peek().job().status());
        }
    }

    @Test
    void staleOwnerRejectedAfterLeaseTakeOver() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult first = t.service.claimNext("worker-A");
            String id = first.job().id();
            String tokenA = first.leaseToken();
            log.info("输入: A 领取 id={} token={} ttl=1000ms", id, tokenA);

            // A 的租约过期，回收器回收，B 重新领取（接管）
            t.clock.fastForward(1_001);
            int reaped = t.reaper.reapExpiredOnce();
            log.info("判定: 过期回收 reaped={} status 回 WAITING", reaped);
            assertEquals(1, reaped);
            assertEquals(JobStatus.WAITING, t.service.getJob(id).status());
            assertEquals(true, t.service.getJob(id).notBeforeMillis() > t.clock.nowMillis(),
                    "回收后应处于退避窗口内，暂不可派发");

            // 越过指数退避窗口后才能重新派发
            t.clock.fastForward(101);
            JobService.ClaimResult second = t.service.claimNext("worker-B");
            String tokenB = second.leaseToken();
            log.info("判定: B 接管 id={} worker={} tokenB={}", id, second.job().leaseHolder(), tokenB);
            assertNotEquals(tokenA, tokenB);

            // A 用旧 token 续租 / 完成：都必须被拒绝，且原因可区分
            JobApiException renewEx = assertThrows(JobApiException.class,
                    () -> t.service.renew(id, tokenA));
            log.info("判定: A 旧 token 续租 code={} msg={}", renewEx.code(), renewEx.getMessage());
            assertEquals(ErrorCode.LEASE_TAKEN_OVER, renewEx.code());

            JobApiException completeEx = assertThrows(JobApiException.class,
                    () -> t.service.complete(id, tokenA, true, "late"));
            log.info("判定: A 旧 token 完成 code={}", completeEx.code());
            assertEquals(ErrorCode.LEASE_TAKEN_OVER, completeEx.code());
        }
    }

    @Test
    void expiredLeaseRenewAndCompleteAreRejected() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult claim = t.service.claimNext("w1");
            log.info("输入: 领取后租约 1000ms 到期");

            t.clock.fastForward(1_001);
            JobApiException renewEx = assertThrows(JobApiException.class,
                    () -> t.service.renew(claim.job().id(), claim.leaseToken()));
            log.info("判定: 过期续租 code={} msg={}", renewEx.code(), renewEx.getMessage());
            assertEquals(ErrorCode.LEASE_EXPIRED, renewEx.code());

            JobApiException completeEx = assertThrows(JobApiException.class,
                    () -> t.service.complete(claim.job().id(), claim.leaseToken(), true, null));
            log.info("判定: 过期完成 code={}", completeEx.code());
            assertEquals(ErrorCode.LEASE_EXPIRED, completeEx.code());
        }
    }

    @Test
    void renewKeepsLeaseAlive() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult claim = t.service.claimNext("w1");
            String id = claim.job().id();

            t.clock.fastForward(800);
            JobView renewed = t.service.renew(id, claim.leaseToken());
            log.info("输入: 800ms 后续租，新到期={}", renewed.leaseExpiresAtMillis());

            t.clock.fastForward(800); // 距续租又过 800ms（累计墙钟 1600ms），仍在新租约窗口内
            JobView again = t.service.renew(id, claim.leaseToken());
            log.info("判定: 续租后 800ms 仍可再次续租，到期={}", again.leaseExpiresAtMillis());

            // 续租期间回收器扫描不应回收它
            int reaped = t.reaper.reapExpiredOnce();
            log.info("判定: 有效租约不被回收 reaped={}", reaped);
            assertEquals(0, reaped);
            assertEquals(JobStatus.RUNNING, t.service.getJob(id).status());
        }
    }

    @Test
    void duplicateCompletionIsIdempotentAndWinnerStays() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult claim = t.service.claimNext("w1");
            String id = claim.job().id();

            JobService.CompleteResult first = t.service.complete(id, claim.leaseToken(), true, "v1");
            long firstVersion = first.job().version();
            log.info("判定: 首次完成 status={} version={} result=v1", first.job().status(), firstVersion);
            assertEquals(JobStatus.SUCCEEDED, first.job().status());

            // 执行方重试，携带相同 token 再提交一个不同结果：不能覆盖首次结果
            JobService.CompleteResult dup = t.service.complete(id, claim.leaseToken(), false, "v2-should-not-win");
            log.info("判定: 重复提交 duplicate={} 结果保持={} status={} version={}",
                    dup.duplicate(), dup.job().completion().result(), dup.job().status(), dup.job().version());
            assertEquals(true, dup.duplicate());
            assertEquals("v1", dup.job().completion().result());
            assertEquals(JobStatus.SUCCEEDED, dup.job().status());
            assertEquals(firstVersion, dup.job().version(), "重复提交不得产生新版本（无静默丢更新）");
        }
    }

    @Test
    void wrongTokenWhileLeaseValidIsConflict() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult claim = t.service.claimNext("w1");
            JobApiException ex = assertThrows(JobApiException.class,
                    () -> t.service.complete(claim.job().id(), "someone-elses-token", true, null));
            log.info("判定: 有效租约窗口内伪造 token code={}", ex.code());
            assertEquals(ErrorCode.LEASE_TAKEN_OVER, ex.code());
            assertNull(t.service.getJob(claim.job().id()).completion());
        }
    }
}
