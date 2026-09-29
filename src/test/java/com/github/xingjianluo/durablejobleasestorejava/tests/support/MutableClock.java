package com.github.xingjianluo.durablejobleasestorejava.tests.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用时钟：可手动前进，用于精确制造租约过期、退避窗口、游标过期。
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public static MutableClock startAt(Instant t) {
        return new MutableClock(t);
    }

    public void advance(Duration d) {
        this.now = this.now.plus(d);
    }

    public void set(Instant t) {
        this.now = t;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
