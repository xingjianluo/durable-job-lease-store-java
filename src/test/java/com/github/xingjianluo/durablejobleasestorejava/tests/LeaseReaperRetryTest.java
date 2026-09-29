package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约超时回收、指数退避逐步拉长、达到重试上限后置 FAILED 不再重派。
 */
class LeaseReaperRetryTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperRetryTest.class);

    @Test
    void expiredLeaseIsReclaimedThenBackoffThenReclaimedAgain() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult c1 = t.service.claimNext("w1");
            String id = c1.job().id();
            log.info("输入: 首次领取 id={} attempts=1, 租约 1000ms", id);

            t.clock.fastForward(1_001);
            int n1 = t.reaper.reapExpiredOnce();
            JobView after1 = t.service.getJob(id);
            log.info("判定: 第1次回收 n={} status={} attempts={} notBefore-now={}ms",
                    n1, after1.status(), after1.attempts(), after1.notBeforeMillis() - t.clock.nowMillis());
            assertEquals(1, n1);
            assertEquals(JobStatus.WAITING, after1.status());
            assertEquals(1, after1.attempts());
            assertTrue(after1.notBeforeMillis() > t.clock.nowMillis(), "应设置退避，未到点不可派发");

            // 退避窗口内领取不到
            t.clock.fastForward(99);
            assertFalse(t.service.claimNext("w2").claimed(), "退避未结束不应被领取");
            t.clock.fastForward(2);
            JobService.ClaimResult c2 = t.service.claimNext("w2");
            log.info("判定: 退避结束后重新派发 claimed={} attempts={}", c2.claimed(), c2.job().attempts());
            assertTrue(c2.claimed());
            assertEquals(2, c2.job().attempts());

            t.clock.fastForward(1_001);
            int n2 = t.reaper.reapExpiredOnce();
            JobView after2 = t.service.getJob(id);
            long backoff2 = after2.notBeforeMillis() - t.clock.nowMillis();
            log.info("判定: 第2次回收 status={} attempts={} 第二次退避={}ms（应大于第一次的100ms）",
                    after2.status(), after2.attempts(), backoff2);
            assertEquals(1, n2);
            assertTrue(backoff2 > 100, "退避应逐步拉长，第二次为 base*2=200ms");
        }
    }

    @Test
    void retryLimitExceededJobFailsTerminallyAndNeverRedispatches() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            String id = null;

            // maxAttempts=3：共允许 3 次尝试；前 2 次超时回收重派，第 3 次超时后置 FAILED
            for (int attempt = 1; attempt <= 3; attempt++) {
                // 每次尝试前越过退避（第一次 notBefore=now）
                t.clock.fastForward(attempt == 1 ? 0 : 10_001);
                JobService.ClaimResult c = t.service.claimNext("w" + attempt);
                if (attempt == 1) {
                    id = c.job().id();
                }
                assertTrue(c.claimed(), "第 " + attempt + " 次尝试应可领取");
                log.info("输入: 第{}次领取 id={} attempts={}", attempt, id, c.job().attempts());

                t.clock.fastForward(1_001);
                int n = t.reaper.reapExpiredOnce();
                JobView after = t.service.getJob(id);
                log.info("判定: 第{}次租约过期回收 n={} status={} attempts={} completion={}",
                        attempt, n, after.status(), after.attempts(),
                        after.completion() == null ? null : after.completion().result());
                assertEquals(1, n);
                if (attempt < 3) {
                    assertEquals(JobStatus.WAITING, after.status());
                } else {
                    assertEquals(JobStatus.FAILED, after.status(), "达到上限应进入失败终态");
                }
            }

            // 再久也不会被重新派发
            t.clock.fastForward(1_000_000);
            JobService.ClaimResult late = t.service.claimNext("w-late");
            log.info("判定: 超过上限很久后再次领取 claimed={}（必须为 false）", late.claimed());
            assertFalse(late.claimed());
            assertEquals(JobStatus.FAILED, t.service.getJob(id).status());
        }
    }

    @Test
    void workerReportedFailureAlsoUsesBackoffAndLimit() throws Exception {
        try (TestSupport t = new TestSupport()) {
            t.service.createJob("j", null);
            JobService.ClaimResult c = t.service.claimNext("w1");
            String id = c.job().id();

            JobService.CompleteResult r = t.service.complete(id, c.leaseToken(), false, "boom");
            log.info("判定: 主动上报失败 status={} attempts={} 退避至 notBefore={}",
                    r.job().status(), r.job().attempts(), r.job().notBeforeMillis());
            assertEquals(JobStatus.WAITING, r.job().status());
            assertEquals(1, r.job().attempts());

            // 再领取、再失败两次（第 2、3 次尝试），第 3 次失败应直接 FAILED
            for (int attempt = 2; attempt <= 3; attempt++) {
                t.clock.fastForward(10_001);
                JobService.ClaimResult cc = t.service.claimNext("w" + attempt);
                assertTrue(cc.claimed());
                JobService.CompleteResult rr = t.service.complete(id, cc.leaseToken(), false, "boom" + attempt);
                log.info("判定: 第{}次失败 status={} attempts={}", attempt, rr.job().status(), rr.job().attempts());
                if (attempt < 3) {
                    assertEquals(JobStatus.WAITING, rr.job().status());
                } else {
                    assertEquals(JobStatus.FAILED, rr.job().status());
                }
            }
            // FAILED 后再次提交完成（即使伪造）走终态幂等：无 completion 非成功记录时... 此处 completion 存在
            JobService.CompleteResult dup = t.service.complete(id, "whatever", true, "late-success");
            log.info("判定: 失败终态后重复提交 duplicate={} 终态保持={}",
                    dup.duplicate(), dup.job().status());
            assertEquals(true, dup.duplicate());
            assertEquals(JobStatus.FAILED, dup.job().status());
        }
    }
}
