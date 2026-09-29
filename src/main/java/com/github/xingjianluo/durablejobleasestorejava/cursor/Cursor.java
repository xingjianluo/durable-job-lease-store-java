package com.github.xingjianluo.durablejobleasestorejava.cursor;

/**
 * 游标内容：稳定排序下“本页最后一条记录”的锚点 + 签发时间。
 *
 * @param anchorId  锚点任务 ID（键集分页的最后一条）
 * @param issuedAt  游标签发时间（epoch millis），用于过期判定
 */
public record Cursor(String anchorId, long issuedAt) {
}
