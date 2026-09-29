package com.github.xingjianluo.durablejobleasestorejava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.domain.TaskStatus;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

/**
 * 文件持久化任务存储：
 * <ul>
 *   <li>内存用 {@link ConcurrentHashMap}，变更走 {@code compute}（对单个 key 加锁），
 *       保证同一任务的领取/完成并发互斥，CAS 语义清晰；</li>
 *   <li>每次变更后把全量快照原子写入 JSON 文件（同目录临时文件 + ATOMIC_MOVE），
 *       本地直接运行即可获得掉盘恢复能力，不依赖外部数据库。</li>
 * </ul>
 */
@Component
public class FileTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(FileTaskStore.class);
    private static final TypeReference<List<JobTask>> LIST_TYPE = new TypeReference<>() {};

    private final ConcurrentMap<String, JobTask> tasks = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LeaseProperties properties;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public FileTaskStore(Clock clock, LeaseProperties properties) {
        this.clock = clock;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void load() {
        Path file = Path.of(properties.getStoreFile());
        if (!Files.exists(file)) {
            log.info("持久化文件不存在，以空存储启动: {}", file.toAbsolutePath());
            return;
        }
        try {
            List<JobTask> loaded = objectMapper.readValue(Files.readAllBytes(file), LIST_TYPE);
            loaded.forEach(t -> tasks.put(t.getId(), t));
            log.info("从快照恢复任务 {} 条: {}", loaded.size(), file.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("读取任务快照失败: " + file, e);
        }
    }

    @Override
    public JobTask insert(JobTask task) {
        JobTask previous = tasks.putIfAbsent(task.getId(), task);
        if (previous != null) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "任务 ID 冲突: " + task.getId());
        }
        persist("insert:" + task.getId());
        log.debug("insert 任务 id={}, status={}", task.getId(), task.getStatus());
        return task;
    }

    @Override
    public Optional<JobTask> findById(String id) {
        JobTask t = tasks.get(id);
        return Optional.ofNullable(t == null ? null : copy(t));
    }

    @Override
    public List<JobTask> findPage(String anchorId, int limit) {
        // 键集分页：id 为全局唯一单调键，只取严格大于锚点的记录。
        // 新增（ID 更大或更小）与删除都不会让固定窗口内的记录重复或漏读。
        List<JobTask> all = new ArrayList<>(tasks.size());
        for (JobTask t : tasks.values()) {
            if (anchorId == null || t.getId().compareTo(anchorId) > 0) {
                all.add(copy(t));
            }
        }
        all.sort(Comparator.comparing(JobTask::getId));
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(0, limit));
    }

    @Override
    public JobTask mutate(String id, UnaryOperator<JobTask> mutation) {
        // compute 对该 key 的 bin 加锁，mutation 在锁内执行；
        // 业务校验失败直接抛出（不会落盘），保证“校验+写入”原子。
        JobTask[] applied = new JobTask[1];
        tasks.compute(id, (key, current) -> {
            if (current == null) {
                throw new ApiException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + key);
            }
            JobTask next = mutation.apply(copy(current));
            if (next == null) {
                return current; // 业务放弃修改
            }
            next.setUpdatedAt(Instant.now(clock));
            applied[0] = copy(next);
            return next;
        });
        if (applied[0] != null) {
            persist("mutate:" + id);
        }
        return applied[0] != null ? applied[0] : tasks.containsKey(id) ? findById(id).orElseThrow() : null;
    }

    @Override
    public List<JobTask> findExpiredLeases() {
        Instant now = Instant.now(clock);
        List<JobTask> result = new ArrayList<>();
        for (JobTask t : tasks.values()) {
            if (t.getStatus() == TaskStatus.RUNNING
                    && t.getLeaseExpiresAt() != null
                    && t.getLeaseExpiresAt().isBefore(now)) {
                result.add(copy(t));
            }
        }
        result.sort(Comparator.comparing(JobTask::getId));
        return result;
    }

    @Override
    public List<JobTask> findDispatchable(int limit) {
        Instant now = Instant.now(clock);
        List<JobTask> result = new ArrayList<>();
        for (JobTask t : tasks.values()) {
            if (t.getStatus() == TaskStatus.PENDING
                    && (t.getNotBefore() == null || !t.getNotBefore().isAfter(now))) {
                result.add(copy(t));
            }
        }
        result.sort(Comparator.comparing(JobTask::getId));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    }

    @Override
    public boolean deleteById(String id) {
        boolean removed = tasks.remove(id) != null;
        if (removed) {
            persist("delete:" + id);
            log.info("删除任务 id={}", id);
        }
        return removed;
    }

    @Override
    public void clear() {
        tasks.clear();
        persist("clear");
    }

    /** 返回防御性拷贝，避免调用方在锁外修改实体。 */
    private static JobTask copy(JobTask s) {
        try {
            JobTask t = new JobTask();
            t.setId(s.getId());
            t.setPayload(s.getPayload());
            t.setStatus(s.getStatus());
            t.setWorkerId(s.getWorkerId());
            t.setLeaseToken(s.getLeaseToken());
            t.setLeaseExpiresAt(s.getLeaseExpiresAt());
            t.setTimeoutCount(s.getTimeoutCount());
            t.setAttemptCount(s.getAttemptCount());
            t.setNotBefore(s.getNotBefore());
            t.setLastError(s.getLastError());
            t.setCreatedAt(s.getCreatedAt());
            t.setUpdatedAt(s.getUpdatedAt());
            return t;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 全量快照原子落盘。 */
    private void persist(String reason) {
        synchronized (writeLock) {
            Path target = Path.of(properties.getStoreFile());
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                List<JobTask> snapshot = tasks.values().stream()
                        .sorted(Comparator.comparing(JobTask::getId))
                        .toList();
                byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(snapshot);
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                log.debug("快照已落盘 reason={}, size={}, file={}",
                        reason, snapshot.size(), target);
            } catch (IOException e) {
                throw new IllegalStateException("任务快照落盘失败: " + target, e);
            }
        }
    }
}
