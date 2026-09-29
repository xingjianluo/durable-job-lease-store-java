package com.github.xingjianluo.durablejobleasestorejava.domain;

/**
 * 对外只读视图（不暴露租约 token）。
 */
public record JobView(
        String id,
        String type,
        String payload,
        long seq,
        long createdAtMillis,
        long updatedAtMillis,
        long version,
        JobStatus status,
        int attempts,
        long notBeforeMillis,
        String leaseHolder,
        Long leaseExpiresAtMillis,
        CompletionRecord completion) {

    public static JobView of(Job j) {
        return new JobView(
                j.getId(), j.getType(), j.getPayload(), j.getSeq(),
                j.getCreatedAtMillis(), j.getUpdatedAtMillis(), j.getVersion(),
                j.getStatus(), j.getAttempts(), j.getNotBeforeMillis(),
                j.getLeaseHolder(),
                j.getLeaseExpiresAtMillis() == 0L ? null : j.getLeaseExpiresAtMillis(),
                j.getCompletion());
    }
}
