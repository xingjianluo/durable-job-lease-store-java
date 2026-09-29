package com.github.xingjianluo.durablejobleasestorejava.domain;

/**
 * 任务状态机：
 * <pre>
 * PENDING --claim()--------> RUNNING
 * RUNNING --succeed()------> SUCCEEDED
 * RUNNING --fail()(可重试)--> PENDING (带退避时间)
 * RUNNING --fail()(次数尽)--> FAILED
 * RUNNING --timeout 回收 ---> PENDING / FAILED
 * PENDING/RUNNING --cancel-> CANCELLED
 * </pre>
 * SUCCEEDED / FAILED / CANCELLED 为终态。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
