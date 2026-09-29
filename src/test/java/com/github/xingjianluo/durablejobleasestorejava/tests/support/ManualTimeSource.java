package com.github.xingjianluo.durablejobleasestorejava.tests.support;

import com.github.xingjianluo.durablejobleasestorejava.time.TimeSource;

/**
 * 测试用可控时钟：初始固定，可 fastForward 快进。
 */
public class ManualTimeSource implements TimeSource {
    private long now;

    public ManualTimeSource() {
        this(1_700_000_000_000L);
    }

    public ManualTimeSource(long startMillis) {
        this.now = startMillis;
    }

    public synchronized void fastForward(long deltaMillis) {
        this.now += deltaMillis;
    }

    public synchronized void setNow(long millis) {
        this.now = millis;
    }

    @Override
    public synchronized long nowMillis() {
        return now;
    }
}
