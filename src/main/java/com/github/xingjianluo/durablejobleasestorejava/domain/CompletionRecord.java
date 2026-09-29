package com.github.xingjianluo.durablejobleasestorejava.domain;

/**
 * 完成结果回执：成功或失败都会被持久化，用于重复提交时幂等返回。
 */
public record CompletionRecord(boolean succeeded, String result, String workerId, long completedAtMillis) {
}
