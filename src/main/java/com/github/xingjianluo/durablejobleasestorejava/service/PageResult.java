package com.github.xingjianluo.durablejobleasestorejava.service;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;

import java.util.List;

/**
 * 列表分页结果。
 *
 * @param items    本页条目
 * @param nextCursor 下一页游标；无更多数据时为 null
 * @param hasMore  是否还有下一页
 */
public record PageResult(List<JobTask> items, String nextCursor, boolean hasMore) {
}
