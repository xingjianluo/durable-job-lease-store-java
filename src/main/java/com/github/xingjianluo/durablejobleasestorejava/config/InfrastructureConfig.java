package com.github.xingjianluo.durablejobleasestorejava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 基础设施 Bean：可替换的时钟，便于测试租约过期。
 */
@Configuration
public class InfrastructureConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
