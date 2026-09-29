package com.github.xingjianluo.durablejobleasestorejava.tests.support;

import com.github.xingjianluo.durablejobleasestorejava.config.JobProperties;
import com.github.xingjianluo.durablejobleasestorejava.cursor.CursorService;
import com.github.xingjianluo.durablejobleasestorejava.lease.LeaseReaper;
import com.github.xingjianluo.durablejobleasestorejava.lease.LeaseService;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.store.PersistentJobStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 纯手工装配的测试夹具：短租约(1s)、短退避、可控时钟，隔离的临时数据目录。
 */
public class TestSupport implements AutoCloseable {

    public final ManualTimeSource clock;
    public final JobProperties props;
    public final CursorService cursors;
    public final PersistentJobStore store;
    public final LeaseService leases;
    public final LeaseReaper reaper;
    public final JobService service;
    public final Path dataDir;

    public TestSupport() throws Exception {
        this(1_700_000_000_000L, Files.createTempDirectory("jobstore-test-"));
    }

    public TestSupport(long startClock, Path dataDir) {
        this.clock = new ManualTimeSource(startClock);
        this.dataDir = dataDir;
        this.props = new JobProperties();
        this.props.setLeaseTtlMillis(1_000);
        this.props.setReaperIntervalMillis(60_000); // 测试不依赖定时线程，手动触发 reaper
        this.props.setMaxAttempts(3);
        this.props.setBaseBackoffMillis(100);
        this.props.setMaxBackoffMillis(10_000);
        this.props.setCursorTtlMillis(3_600_000);
        this.props.setDataDir(dataDir.toString());
        this.cursors = new CursorService(clock, props);
        this.store = new PersistentJobStore(props, clock, cursors);
        this.leases = new LeaseService(props);
        this.reaper = new LeaseReaper(store, leases, clock);
        this.service = new JobService(store, leases, clock);
    }

    /** 用相同数据目录重建组件，模拟进程重启后的恢复。 */
    public TestSupport restart() {
        long t = clock.nowMillis();
        return new TestSupport(t, dataDir) {
        };
    }

    @Override
    public void close() throws Exception {
        // 临时目录保留与否对测试无影响；JVM 退出后由系统清理
    }
}
