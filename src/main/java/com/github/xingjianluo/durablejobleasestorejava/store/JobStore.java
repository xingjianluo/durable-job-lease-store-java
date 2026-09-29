package com.github.xingjianluo.durablejobleasestorejava.store;

import com.github.xingjianluo.durablejobleasestorejava.cursor.CursorService;
import com.github.xingjianluo.durablejobleasestorejava.domain.Job;
import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 任务存储抽象。所有变异方法在存储层以原子方式完成（单机内置锁），
 * 并通过 version 乐观号 + 租约 token 双重检测防止并发覆盖。
 */
public interface JobStore {

    /** 游标分页结果：items 为当前页，nextCursor 为空表示没有下一页；anchorFound=false 表示锚点记录已删除。 */
    record Page(List<JobView> items, String nextCursor, boolean anchorFound) {
    }

    Job create(String type, String payload);

    Optional<Job> get(String id);

    /**
     * 稳定顺序游标分页：排序键为 (createdAtMillis, seq) 升序，seq 全局单调不受删除影响。
     * cursor 为空表示第一页；anchorFound=false 表示游标锚点记录已不存在（被删除）。
     */
    Page list(int limit, String cursor);

    /** 取下一个可派发任务（WAITING 且 notBefore <= now）；仅读取快照，不修改状态。 */
    Optional<Job> nextDispatchable(long nowMillis);

    /**
     * 条件领取：任务必须处于 WAITING，且 version 等于 expectedVersion。
     * @throws com.github.xingjianluo.durablejobleasestorejava.error.JobApiException VERSION_CONFLICT
     */
    Job claimIfWaiting(String id, long expectedVersion, String workerId, String token,
                       long expiresAtMillis, long nowMillis);

    /**
     * 条件提交修改：version 必须等于 expected.version（防静默丢更新）。
     * mutation 在锁内对副本执行，成功后持久化并返回新副本。
     * @throws com.github.xingjianluo.durablejobleasestorejava.error.JobApiException VERSION_CONFLICT
     */
    Job compareAndUpdate(Job expected, Consumer<Job> mutation, long nowMillis);

    /** 回收器扫描用：返回所有执行中(RUNNING)任务的快照。 */
    List<Job> allRunningSnapshot();

    boolean deleteTerminal(String id);

    /** 测试隔离用：返回全部任务 id。 */
    java.util.List<String> allJobIds();

    /** 测试隔离用：无视状态强制删除。 */
    boolean forceDeleteForTesting(String id);

    CursorService cursorService();
}
