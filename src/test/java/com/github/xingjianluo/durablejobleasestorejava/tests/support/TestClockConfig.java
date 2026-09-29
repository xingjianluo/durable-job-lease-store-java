package com.github.xingjianluo.durablejobleasestorejava.tests.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试环境以 MutableClock 替换系统时钟（固定起点，可手动推进）。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant FIXED_START = Instant.parse("2026-09-29T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_START);
    }

    @Bean(name = "systemClockOverride")
    public Clock clock(MutableClock mutableClock) {
        return mutableClock;
    }
}
