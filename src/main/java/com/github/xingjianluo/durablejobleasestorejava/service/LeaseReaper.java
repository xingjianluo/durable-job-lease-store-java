package com.github.xingjianluo.durablejobleasestorejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时扫描并回收过期租约：超时任务按退避重新派发，次数用尽置 FAILED。
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final TaskService taskService;

    public LeaseReaper(TaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(fixedDelayString = "${durable.reaper-interval:5000}")
    public void tick() {
        try {
            int reaped = taskService.reapExpiredLeases();
            if (reaped > 0) {
                log.info("租约回收定时器处理任务数={}", reaped);
            }
        } catch (Exception e) {
            // 扫描失败不应杀掉调度线程
            log.error("租约回收定时器执行异常", e);
        }
    }
}
