package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 JSON 快照持久化：重建存储（模拟进程重启）后，任务状态/租约/重试计数/seq 均恢复。
 */
class PersistenceRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceRecoveryTest.class);

    @Test
    void snapshotSurvivesRestart() throws Exception {
        Path dir = Files.createTempDirectory("jobstore-restart-");
        long startClock = 1_700_000_000_000L;

        String succeededId;
        String waitingId;
        String runningId;
        String runningToken;
        long runningExpiresAt;
        int runningAttempts;
        long runningVersion;
        long seqAfter;

        try (TestSupport t = new TestSupport(startClock, dir)) {
            JobView j1 = t.service.createJob("done", null);
            succeededId = j1.id();
            JobService.ClaimResult c1 = t.service.claimNext("w1");
            t.service.complete(c1.job().id(), c1.leaseToken(), true, "persisted-result");

            waitingId = t.service.createJob("waiting", null).id();

            JobView j3 = t.service.createJob("running", null);
            runningId = j3.id();
            // 越过 j2/waiting 的初始顺序：claim 按 seq 取最早可派发，j2 会先被领取；
            // 因此先把 j2 取消，再领取 j3
            t.service.cancel(waitingId);
            // waitingId 已取消，再建一个真正保持 WAITING 的
            String trulyWaiting = t.service.createJob("waiting2", null).id();
            waitingId = trulyWaiting;

            t.clock.fastForward(10_001);
            JobService.ClaimResult c3 = t.service.claimNext("w2");
            assertEquals(runningId, c3.job().id());
            runningToken = c3.leaseToken();
            runningExpiresAt = c3.job().leaseExpiresAtMillis();
            runningAttempts = c3.job().attempts();
            runningVersion = c3.job().version();
            seqAfter = t.store.seqCounterValue();
            log.info("重启前: 成功={} 待领取={} 执行中={} seq={}", succeededId, waitingId, runningId, seqAfter);
            assertTrue(Files.exists(dir.resolve("snapshot.json")), "运行期间应已生成快照文件");
        }

        // 模拟进程重启：用同一数据目录全新装配，时钟从重启时刻继续
        try (TestSupport t2 = new TestSupport(startClock + 50_000L, dir)) {
            JobView done = t2.service.getJob(succeededId);
            JobView waiting = t2.service.getJob(waitingId);
            JobView running = t2.service.getJob(runningId);

            log.info("重启后: done.status={} completion={}", done.status(), done.completion().result());
            log.info("重启后: waiting.status={} running.status={} running.leaseHolder={}",
                    waiting.status(), running.status(), running.leaseHolder());

            assertEquals(JobStatus.SUCCEEDED, done.status());
            assertEquals("persisted-result", done.completion().result());
            assertEquals(JobStatus.WAITING, waiting.status());
            assertEquals(JobStatus.RUNNING, running.status());
            assertEquals("w2", running.leaseHolder());
            assertEquals(runningExpiresAt, running.leaseExpiresAtMillis());
            assertEquals(runningAttempts, running.attempts());
            assertEquals(runningVersion, running.version());

            // 重启时租约按墙钟已过期（重启时钟 +50s）：旧 token 必须失效，回收器可回收
            t2.reaper.reapExpiredOnce();
            JobView reaped = t2.service.getJob(runningId);
            log.info("判定: 重启后过期租约被回收 status={} attempts 保持={}", reaped.status(), reaped.attempts());
            assertEquals(JobStatus.WAITING, reaped.status());
            assertEquals(runningAttempts, reaped.attempts(), "attempts 计数跨重启保持，不重复计数");

            // seq 单调不回退：新建任务的 seq 必须大于重启前
            JobView after = t2.service.createJob("after-restart", null);
            assertNotNull(after.id());
            assertTrue(after.seq() > seqAfter, "seq 必须跨重启继续单调递增");
            log.info("判定: 重启后新建任务 seq={} > 重启前 seq={}", after.seq(), seqAfter);
        }
    }
}
