package com.github.xingjianluo.durablejobleasestorejava.time;

import org.springframework.stereotype.Component;

@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
