package com.github.xingjianluo.durablejobleasestorejava.lease;

import com.github.xingjianluo.durablejobleasestorejava.config.JobProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 租约参数计算：
 * <ul>
 *   <li>租约 token 为不可猜测的随机值，作为持有者身份凭证；</li>
 *   <li>失败重试退避：第 n 次（n 从 1 起）重新派发需等待 min(base * 2^(n-1), max)，逐步拉长；</li>
 *   <li>attempts 已达 maxAttempts 时不再重新派发（由调用方置为 FAILED）。</li>
 * </ul>
 */
@Component
public class LeaseService {

    private final JobProperties props;

    public LeaseService(JobProperties props) {
        this.props = props;
    }

    public String newToken() {
        return UUID.randomUUID().toString();
    }

    public long leaseTtlMillis() {
        return props.getLeaseTtlMillis();
    }

    public long expiresAt(long nowMillis) {
        return nowMillis + props.getLeaseTtlMillis();
    }

    public int maxAttempts() {
        return props.getMaxAttempts();
    }

    /** 给定已尝试次数 attempts（本次失败后），下次可派发时间；返回 Long.MAX_VALUE 表示不应再派发。 */
    public long nextDispatchTime(long nowMillis, int attemptsAfterFailure) {
        if (attemptsAfterFailure >= props.getMaxAttempts()) {
            return Long.MAX_VALUE;
        }
        long base = props.getBaseBackoffMillis();
        long max = props.getMaxBackoffMillis();
        int shift = attemptsAfterFailure - 1; // 第一次重试 shift=0
        long backoff;
        if (shift >= 63) {
            backoff = max;
        } else {
            try {
                backoff = Math.multiplyExact(base, 1L << shift);
            } catch (ArithmeticException e) {
                backoff = max;
            }
        }
        return nowMillis + Math.min(backoff, max);
    }
}
