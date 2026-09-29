package com.github.xingjianluo.durablejobleasestorejava.domain;

/**
 * 任务生命周期状态：
 *
 * <pre>
 *   WAITING(待领取) ──claim──▶ RUNNING(执行中)
 *      ▲   │                      │
 *      │   └────cancel────────────┼──▶ CANCELED(取消)
 *      │                          ├──complete(success)──▶ SUCCEEDED(成功)
 *      │                          └──complete(failure)──▶ FAILED(失败, 终态)
 *      └────────租约超时/可重试失败（attempts < maxAttempts）─────────
 * </pre>
 *
 * SUCCEEDED / FAILED / CANCELED 为终态，不再流转。
 */
public enum JobStatus {
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
