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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 状态机合法/非法流转：断言每种拒绝都能落到明确可区分的错误码。
 */
class StateTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(StateTransitionTest.class);

    @Test
    void happyPathWaitingToRunningToSucceeded() throws Exception {
        try (TestSupport t = new TestSupport()) {
            JobView job = t.service.createJob("email", "hello");
            log.info("输入: create type=email -> id={} status={}", job.id(), job.status());

            JobService.ClaimResult claim = t.service.claimNext("w1");
            log.info("判定: claim claimed={} status={}", claim.claimed(), claim.job().status());
            assertEquals(JobStatus.RUNNING, claim.job().status());

            JobService.CompleteResult done = t.service.complete(claim.job().id(), claim.leaseToken(), true, "ok");
            log.info("判定: complete succeeded={} duplicate={} 终态={}",
                    done.job().completion().succeeded(), done.duplicate(), done.job().status());
            assertEquals(JobStatus.SUCCEEDED, done.job().status());
            assertEquals(false, done.duplicate());
        }
    }

    @Test
    void cancelWaitingOnlyAndIllegalCancelRunning() throws Exception {
        try (TestSupport t = new TestSupport()) {
            JobView a = t.service.createJob("a", null);
            t.service.createJob("b", null);

            JobView canceled = t.service.cancel(a.id());
            log.info("输入: cancel WAITING id={} -> status={}", a.id(), canceled.status());
            assertEquals(JobStatus.CANCELED, canceled.status());

            JobService.ClaimResult claim = t.service.claimNext("w1");
            log.info("前置: 领取 id={} -> {}", claim.job().id(), claim.job().status());
            JobApiException ex = assertThrows(JobApiException.class, () -> t.service.cancel(claim.job().id()));
            log.info("判定: cancel RUNNING 被拒绝 code={} msg={}", ex.code(), ex.getMessage());
            assertEquals(ErrorCode.ILLEGAL_TRANSITION, ex.code());

            // 已取消任务重复取消：幂等返回同一状态，不抛异常
            JobView cancelAgain = t.service.cancel(a.id());
            log.info("判定: 重复 cancel 已取消任务幂等返回 status={}", cancelAgain.status());
            assertEquals(JobStatus.CANCELED, cancelAgain.status());
        }
    }

    @Test
    void cannotCompleteNonRunningTask() throws Exception {
        try (TestSupport t = new TestSupport()) {
            JobView job = t.service.createJob("x", null);
            log.info("输入: 对 WAITING 任务直接 complete id={}", job.id());
            JobApiException ex = assertThrows(JobApiException.class,
                    () -> t.service.complete(job.id(), "fake-token", true, null));
            log.info("判定: code={} msg={}", ex.code(), ex.getMessage());
            assertEquals(ErrorCode.ILLEGAL_TRANSITION, ex.code());
        }
    }

    @Test
    void terminalTasksRejectAllTransitions() throws Exception {
        try (TestSupport t = new TestSupport()) {
            JobView job = t.service.createJob("x", null);
            JobService.ClaimResult claim = t.service.claimNext("w1");
            t.service.complete(claim.job().id(), claim.leaseToken(), true, "ok");

            JobApiException renewEx = assertThrows(JobApiException.class,
                    () -> t.service.renew(claim.job().id(), claim.leaseToken()));
            log.info("判定: 终态续租被拒绝 code={}", renewEx.code());
            assertEquals(ErrorCode.ILLEGAL_TRANSITION, renewEx.code());

            // 终态后重复 complete 走幂等通道而非异常（ALREADY_COMPLETED 语义通过 duplicate=true 表达）
            JobService.CompleteResult dup = t.service.complete(claim.job().id(), claim.leaseToken(), true, "again");
            log.info("判定: 终态重复提交 duplicate={} 结果保持={}",
                    dup.duplicate(), dup.job().completion().result());
            assertEquals(true, dup.duplicate());
            assertEquals("ok", dup.job().completion().result());
        }
    }
}
