package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.domain.TaskStatus;
import com.github.xingjianluo.durablejobleasestorejava.service.TaskService;
import com.github.xingjianluo.durablejobleasestorejava.store.FileTaskStore;
import com.github.xingjianluo.durablejobleasestorejava.store.TaskStore;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：变更落盘后，重新构造存储（模拟进程重启）数据完整恢复。
 * 不依赖外部数据库，只使用本地 JSON 快照文件。
 */
class PersistenceRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceRecoveryTest.class);

    @TempDir
    Path tempDir;

    private FileTaskStore newStore(String file, MutableClock clock) {
        com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties props =
                new com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties();
        props.setStoreFile(file);
        FileTaskStore store = new FileTaskStore(clock, props);
        store.load(); // 模拟 @PostConstruct
        return store;
    }

    @Test
    void snapshotSurvivesStoreRestart() {
        String file = tempDir.resolve("recover.json").toString();
        MutableClock clock = MutableClock.startAt(Instant.parse("2026-09-29T00:00:00Z"));
        com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties props =
                new com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties();
        props.setStoreFile(file);

        // 第一个“进程”：创建 + 领取
        FileTaskStore store1 = newStore(file, clock);
        TaskService service1 = new TaskService(store1,
                new com.github.xingjianluo.durablejobleasestorejava.cursor.SignedCursorCodec(props, clock),
                props, clock);
        JobTask created = service1.create("persist-me");
        JobTask claimed = service1.claim("worker-A", created.getId());
        log.info("【重启前】id={}, status=RUNNING, owner=worker-A, attempt={}",
                created.getId(), claimed.getAttemptCount());

        // 模拟重启：全新 store 实例从同一文件加载
        MutableClock clock2 = MutableClock.startAt(Instant.parse("2026-09-29T00:00:00Z"));
        FileTaskStore store2 = newStore(file, clock2);
        JobTask restored = store2.findById(created.getId()).orElseThrow();
        log.info("【重启后恢复】id={}, status={}, owner={}, token={}, attempts={}",
                restored.getId(), restored.getStatus(), restored.getWorkerId(),
                restored.getLeaseToken(), restored.getAttemptCount());
        assertEquals(TaskStatus.RUNNING, restored.getStatus());
        assertEquals("worker-A", restored.getWorkerId());
        assertEquals(claimed.getLeaseToken(), restored.getLeaseToken());
        assertEquals(1, restored.getAttemptCount());
        assertEquals("persist-me", restored.getPayload());
        log.info("【判定】状态、租约令牌、尝试次数与载荷均从文件快照完整恢复，无需外部数据库");
    }
}
