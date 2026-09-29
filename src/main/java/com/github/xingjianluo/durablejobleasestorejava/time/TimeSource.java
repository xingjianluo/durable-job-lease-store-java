package com.github.xingjianluo.durablejobleasestorejava.time;

/**
 * 时间来源抽象，测试可注入可控时钟以快速模拟租约过期与退避。
 */
public interface TimeSource {
    long nowMillis();
}
