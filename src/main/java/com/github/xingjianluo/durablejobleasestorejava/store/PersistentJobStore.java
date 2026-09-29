package com.github.xingjianluo.durablejobleasestorejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.durablejobleasestorejava.config.JobProperties;
import com.github.xingjianluo.durablejobleasestorejava.cursor.CursorService;
import com.github.xingjianluo.durablejobleasestorejava.domain.Job;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobStatus;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 单机持久化任务存储：
 * <ul>
 *   <li>内存 Map 索引，所有变异在同一把监视器锁内完成并随即落盘；</li>
 *   <li>每次修改后把全量快照以「临时文件 + 原子 rename」写入 dataDir/snapshot.json，
 *       进程重启自动恢复，不依赖外部数据库；</li>
 *   <li>seq 全局单调递增，作为游标分页的稳定排序键，删除记录不影响其它记录顺序。</li>
 * </ul>
 */
@Component
public class PersistentJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentJobStore.class);
    private static final String SNAPSHOT_FILE = "snapshot.json";

    private final JobProperties props;
    private final TimeSource timeSource;
    private final CursorService cursorService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path snapshotPath;

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private long seqCounter = 0L;

    /** 快照文件结构。 */
    private static class Snapshot {
        public long seq;
        public List<Job> jobs = new ArrayList<>();
    }

    public PersistentJobStore(JobProperties props, TimeSource timeSource, CursorService cursorService) {
        this.props = props;
        this.timeSource = timeSource;
        this.cursorService = cursorService;
        this.snapshotPath = (props.getDataDir() == null || props.getDataDir().isBlank())
                ? null : Path.of(props.getDataDir(), SNAPSHOT_FILE);
        load();
    }

    private void load() {
        if (snapshotPath == null || !Files.exists(snapshotPath)) {
            return;
        }
        try {
            Snapshot snap = mapper.readValue(snapshotPath.toFile(), Snapshot.class);
            this.seqCounter = snap.seq;
            for (Job j : snap.jobs) {
                jobs.put(j.getId(), j);
            }
            log.info("从 {} 恢复 {} 个任务，seq={}", snapshotPath, snap.jobs.size(), snap.seq);
        } catch (IOException e) {
            throw new UncheckedIOException("加载持久化快照失败: " + snapshotPath, e);
        }
    }

    private void persistLocked() {
        if (snapshotPath == null) {
            return;
        }
        Snapshot snap = new Snapshot();
        snap.seq = seqCounter;
        snap.jobs = new ArrayList<>(jobs.values());
        Path tmp = snapshotPath.resolveSibling(SNAPSHOT_FILE + ".tmp");
        try {
            Files.createDirectories(snapshotPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snap);
            try {
                Files.move(tmp, snapshotPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("持久化快照失败: " + snapshotPath, e);
        }
    }

    private static Job copyOf(Job j) {
        Job c = new Job();
        c.setId(j.getId());
        c.setType(j.getType());
        c.setPayload(j.getPayload());
        c.setSeq(j.getSeq());
        c.setCreatedAtMillis(j.getCreatedAtMillis());
        c.setUpdatedAtMillis(j.getUpdatedAtMillis());
        c.setVersion(j.getVersion());
        c.setStatus(j.getStatus());
        c.setAttempts(j.getAttempts());
        c.setNotBeforeMillis(j.getNotBeforeMillis());
        c.setLeaseToken(j.getLeaseToken());
        c.setLeaseHolder(j.getLeaseHolder());
        c.setLeaseExpiresAtMillis(j.getLeaseExpiresAtMillis());
        c.setCompletion(j.getCompletion());
        return c;
    }

    @Override
    public synchronized Job create(String type, String payload) {
        long now = timeSource.nowMillis();
        Job j = new Job();
        j.setId(UUID.randomUUID().toString());
        j.setType(type);
        j.setPayload(payload);
        j.setSeq(++seqCounter);
        j.setCreatedAtMillis(now);
        j.setUpdatedAtMillis(now);
        j.setVersion(1L);
        j.setStatus(JobStatus.WAITING);
        j.setAttempts(0);
        j.setNotBeforeMillis(now);
        jobs.put(j.getId(), j);
        persistLocked();
        log.debug("createJob id={} seq={} type={}", j.getId(), j.getSeq(), type);
        return copyOf(j);
    }

    @Override
    public synchronized Optional<Job> get(String id) {
        Job j = jobs.get(id);
        return j == null ? Optional.empty() : Optional.of(copyOf(j));
    }

    @Override
    public synchronized Page list(int limit, String cursor) {
        List<Job> sorted = jobs.values().stream()
                .sorted(Comparator.comparingLong(Job::getCreatedAtMillis)
                        .thenComparingLong(Job::getSeq))
                .toList();

        int startIdx = 0;
        if (cursor != null && !cursor.isBlank()) {
            CursorService.Anchor anchor = cursorService.decode(cursor);
            boolean found = false;
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).getSeq() == anchor.seq()) {
                    startIdx = i + 1; // 锚点之后继续，天然不重复/不漏
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 游标签名合法但锚点记录已删除：明确可区分的结果
                return new Page(List.of(), null, false);
            }
        }

        List<JobView> items = new ArrayList<>();
        String nextCursor = null;
        int end = Math.min(startIdx + limit, sorted.size());
        for (int i = startIdx; i < end; i++) {
            items.add(JobView.of(sorted.get(i)));
        }
        if (end < sorted.size()) {
            Job last = sorted.get(end - 1);
            nextCursor = cursorService.encode(last.getSeq(), last.getCreatedAtMillis());
        }
        return new Page(items, nextCursor, true);
    }

    @Override
    public synchronized Optional<Job> nextDispatchable(long nowMillis) {
        return jobs.values().stream()
                .filter(j -> j.getStatus() == JobStatus.WAITING)
                .filter(j -> j.getNotBeforeMillis() <= nowMillis)
                .min(Comparator.comparingLong(Job::getSeq))
                .map(PersistentJobStore::copyOf);
    }

    @Override
    public synchronized Job claimIfWaiting(String id, long expectedVersion, String workerId, String token,
                                           long expiresAtMillis, long nowMillis) {
        Job current = jobs.get(id);
        if (current == null) {
            throw new JobApiException(ErrorCode.NOT_FOUND, "任务不存在: " + id);
        }
        if (current.getVersion() != expectedVersion) {
            throw new JobApiException(ErrorCode.VERSION_CONFLICT,
                    "版本冲突：期望 version=" + expectedVersion + "，实际 version=" + current.getVersion());
        }
        if (current.getStatus() != JobStatus.WAITING) {
            // 并发领取：任务已被他人持有有效租约
            throw new JobApiException(ErrorCode.LEASE_CONFLICT,
                    "任务已被其他执行者领取：status=" + current.getStatus()
                            + "，leaseHolder=" + current.getLeaseHolder());
        }
        Job m = copyOf(current);
        m.setStatus(JobStatus.RUNNING);
        m.setLeaseToken(token);
        m.setLeaseHolder(workerId);
        m.setLeaseExpiresAtMillis(expiresAtMillis);
        m.setAttempts(current.getAttempts() + 1);
        m.setVersion(current.getVersion() + 1);
        m.setUpdatedAtMillis(nowMillis);
        jobs.put(id, m);
        persistLocked();
        log.debug("claim id={} worker={} attempts={} leaseExpiresAt={}", id, workerId, m.getAttempts(), expiresAtMillis);
        return copyOf(m);
    }

    @Override
    public synchronized Job compareAndUpdate(Job expected, Consumer<Job> mutation, long nowMillis) {
        Job current = jobs.get(expected.getId());
        if (current == null) {
            throw new JobApiException(ErrorCode.NOT_FOUND, "任务不存在: " + expected.getId());
        }
        if (current.getVersion() != expected.getVersion()) {
            throw new JobApiException(ErrorCode.VERSION_CONFLICT,
                    "版本冲突：期望 version=" + expected.getVersion() + "，实际 version=" + current.getVersion());
        }
        Job m = copyOf(current);
        mutation.accept(m);
        m.setVersion(current.getVersion() + 1);
        m.setUpdatedAtMillis(nowMillis);
        jobs.put(expected.getId(), m);
        persistLocked();
        return copyOf(m);
    }

    @Override
    public synchronized List<Job> allRunningSnapshot() {
        return jobs.values().stream()
                .filter(j -> j.getStatus() == JobStatus.RUNNING)
                .map(PersistentJobStore::copyOf)
                .toList();
    }

    @Override
    public synchronized boolean deleteTerminal(String id) {
        Job current = jobs.get(id);
        if (current == null) {
            return false;
        }
        if (!current.getStatus().isTerminal()) {
            throw new JobApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "只能删除终态任务，当前 status=" + current.getStatus());
        }
        jobs.remove(id);
        persistLocked();
        log.debug("deleteTerminal id={} status={}", id, current.getStatus());
        return true;
    }

    @Override
    public synchronized java.util.List<String> allJobIds() {
        return new java.util.ArrayList<>(jobs.keySet());
    }

    @Override
    public synchronized boolean forceDeleteForTesting(String id) {
        boolean removed = jobs.remove(id) != null;
        if (removed) {
            persistLocked();
        }
        return removed;
    }

    @Override
    public CursorService cursorService() {
        return cursorService;
    }

    /** 当前全局单调序号（测试/诊断用）。 */
    public synchronized long seqCounterValue() {
        return seqCounter;
    }
}
