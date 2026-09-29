package com.github.xingjianluo.durablejobleasestorejava.store;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 任务存储抽象。所有变更方法都以 compare-and-swap 方式工作：
 * 读取 -> 业务修改 -> 以当前版本号条件回写，版本不符则失败（乐观并发控制）。
 */
public interface TaskStore {

    /** 追加新任务（ID 已分配）。 */
    JobTask insert(JobTask task);

    /** 按 ID 读取。 */
    Optional<JobTask> findById(String id);

    /**
     * 键集分页：按 id 升序，取 id 大于 anchorId（null 表示从头）的前 limit 条。
     * 基于稳定排序键，翻页期间新增/删除不会产生重复或漏读。
     */
    List<JobTask> findPage(String anchorId, int limit);

    /**
     * 以乐观锁更新任务：
     * 加载当前值，交给 mutation 产生新值（返回 null 表示放弃更新）；
     * 若期间已被其他线程修改，则 mutation 可能被重试。
     * 返回最终落库的值。
     */
    JobTask mutate(String id, UnaryOperator<JobTask> mutation);

    /**
     * 查找所有需要回收租约的任务：RUNNING 且租约已过期。
     */
    List<JobTask> findExpiredLeases();

    /**
     * 查找所有可派发的 PENDING 任务（notBefore 已到期），按 ID 升序。
     */
    List<JobTask> findDispatchable(int limit);

    /** 删除任务（用于游标指向已删除记录的场景与测试清理）。 */
    boolean deleteById(String id);

    /** 清空全部任务并落盘（测试/重置用）。 */
    void clear();
}
