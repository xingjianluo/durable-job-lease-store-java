package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.store.JobStore;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游标分页：稳定排序、无重复无遗漏；篡改 / 过期 / 锚点删除三类失败原因可区分；翻页中增删不重不漏。
 */
class CursorPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(CursorPaginationTest.class);

    private List<String> createJobs(TestSupport t, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(t.service.createJob("type-" + i, "p" + i).id());
            t.clock.fastForward(1); // createdAt 严格递增，顺序确定
        }
        return ids;
    }

    private List<JobView> walkAll(TestSupport t, int pageSize) {
        List<JobView> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            JobStore.Page page = t.service.listJobs(pageSize, cursor);
            assertTrue(page.anchorFound(), "正常翻页锚点应始终存在");
            all.addAll(page.items());
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
            if (++guard > 1000) {
                throw new AssertionError("分页未终止");
            }
        }
        return all;
    }

    @Test
    void stableOrderAndNoDuplicatesOrGapsAcrossPages() throws Exception {
        try (TestSupport t = new TestSupport()) {
            List<String> ids = createJobs(t, 7);
            List<JobView> all = walkAll(t, 3);
            log.info("输入: 7 个任务，pageSize=3；判定: 共取到 {} 个", all.size());
            assertEquals(7, all.size());
            assertEquals(new HashSet<>(ids), new HashSet<>(all.stream().map(JobView::id).toList()));

            List<Long> seqs = all.stream().map(JobView::seq).toList();
            List<Long> sorted = new ArrayList<>(seqs);
            Collections.sort(sorted);
            assertEquals(sorted, seqs, "必须按 (createdAt,seq) 稳定升序");
            assertEquals(seqs.size(), new HashSet<>(seqs).size(), "不得出现重复条目");
        }
    }

    @Test
    void tamperedCursorIsRejectedAsInvalid() throws Exception {
        try (TestSupport t = new TestSupport()) {
            createJobs(t, 3);
            JobStore.Page first = t.service.listJobs(2, null);
            String cursor = first.nextCursor();
            log.info("输入: 合法游标={}", cursor);

            // 1) 翻转最后一个字符
            char last = cursor.charAt(cursor.length() - 1);
            String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');
            JobApiException e1 = assertThrows(JobApiException.class, () -> t.service.listJobs(2, tampered));
            log.info("判定: 翻转字符篡改 code={} msg={}", e1.code(), e1.getMessage());
            assertEquals(ErrorCode.CURSOR_INVALID, e1.code());

            // 2) 完全乱码
            JobApiException e2 = assertThrows(JobApiException.class,
                    () -> t.service.listJobs(2, "not-a-real-cursor!!!"));
            log.info("判定: 乱码游标 code={}", e2.code());
            assertEquals(ErrorCode.CURSOR_INVALID, e2.code());

            // 3) 用别的密钥签名（手工拼一个结构正确但签名错误的游标）
            String forged = forgeCursorWithOtherSecret(t);
            JobApiException e3 = assertThrows(JobApiException.class, () -> t.service.listJobs(2, forged));
            log.info("判定: 伪造签名 code={}", e3.code());
            assertEquals(ErrorCode.CURSOR_INVALID, e3.code());
        }
    }

    private String forgeCursorWithOtherSecret(TestSupport t) {
        var otherProps = new com.github.xingjianluo.durablejobleasestorejava.config.JobProperties();
        otherProps.setCursorTtlMillis(t.props.getCursorTtlMillis());
        otherProps.setCursorSecret("attacker-secret");
        var other = new com.github.xingjianluo.durablejobleasestorejava.cursor.CursorService(t.clock, otherProps);
        return other.encode(2L, t.clock.nowMillis());
    }

    @Test
    void expiredCursorIsRejectedAsExpired() throws Exception {
        try (TestSupport t = new TestSupport()) {
            createJobs(t, 3);
            String cursor = t.service.listJobs(2, null).nextCursor();
            log.info("输入: 取得游标后快进超过 TTL={}ms", t.props.getCursorTtlMillis());
            t.clock.fastForward(t.props.getCursorTtlMillis() + 1);
            JobApiException e = assertThrows(JobApiException.class, () -> t.service.listJobs(2, cursor));
            log.info("判定: 过期游标 code={} msg={}", e.code(), e.getMessage());
            assertEquals(ErrorCode.CURSOR_EXPIRED, e.code());
        }
    }

    @Test
    void cursorPointingAtDeletedAnchorIsReportedDistinctly() throws Exception {
        try (TestSupport t = new TestSupport()) {
            List<String> ids = createJobs(t, 5);
            // 取第 1 页（2 条），游标锚点 = 第 2 条
            JobStore.Page first = t.service.listJobs(2, null);
            String anchorId = first.items().get(1).id();
            String cursor = first.nextCursor();
            log.info("输入: 锚点任务 id={} 将被删除（先推进到终态）", anchorId);

            // 锚点任务走完生命周期到终态后删除
            t.clock.fastForward(10_001);
            JobService.ClaimResult claim = claimById(t, anchorId);
            t.service.complete(anchorId, claim.leaseToken(), true, null);
            boolean deleted = t.service.deleteTerminalAndReturn(anchorId);
            log.info("判定: 终态删除成功={}（游标仍签名合法、未过期）", deleted);

            JobStore.Page next = t.service.listJobs(2, cursor);
            log.info("判定: 锚点缺失 anchorFound={} items={} nextCursor={}",
                    next.anchorFound(), next.items().size(), next.nextCursor());
            assertFalse(next.anchorFound(), "游标锚点已删除必须给出可区分结果");
            assertTrue(next.items().isEmpty());

            // 未被删除的其它 4 条仍可从第一页完整取到
            List<JobView> rest = walkAll(t, 10);
            Set<String> restIds = new HashSet<>(rest.stream().map(JobView::id).toList());
            assertEquals(4, restIds.size());
            assertFalse(restIds.contains(anchorId));
            for (String id : ids) {
                if (!id.equals(anchorId)) {
                    assertTrue(restIds.contains(id), "删除锚点不得导致其它记录漏掉");
                }
            }
        }
    }

    @Test
    void insertsAndDeletesDuringPagingDoNotDuplicateOrLose() throws Exception {
        try (TestSupport t = new TestSupport()) {
            List<String> ids = createJobs(t, 5);
            JobStore.Page p1 = t.service.listJobs(2, null);
            assertEquals(2, p1.items().size());
            log.info("输入: 第1页取 seq={}", seqs(p1));

            // 翻页期间“新增”：新任务 seq 更大，排在后面，不影响后续游标起点
            List<String> added = createJobs(t, 3);
            // 翻页期间“删除”一条尚未访问的旧任务（置成功终态后删除，选第 4 条）
            String toDelete = ids.get(3);
            t.clock.fastForward(10_001);
            JobService.ClaimResult claim = claimById(t, toDelete);
            t.service.complete(toDelete, claim.leaseToken(), true, null);
            t.service.deleteTerminal(toDelete);
            log.info("翻页中: 新增 {} 条, 删除旧任务 id={}", added.size(), toDelete);

            List<JobView> rest = new ArrayList<>(p1.items());
            String cursor = p1.nextCursor();
            while (cursor != null) {
                JobStore.Page page = t.service.listJobs(2, cursor);
                assertTrue(page.anchorFound());
                rest.addAll(page.items());
                cursor = page.nextCursor();
            }
            List<Long> seqs = rest.stream().map(JobView::seq).sorted().toList();
            log.info("判定: 跨增删翻页共取 {} 条 seqs={}", rest.size(), seqs);
            assertEquals(seqs.size(), new HashSet<>(seqs).size(), "不得重复");

            Set<String> expected = new HashSet<>(ids);
            expected.remove(toDelete);
            expected.addAll(added);
            assertEquals(expected, new HashSet<>(rest.stream().map(JobView::id).toList()),
                    "新增必须取到、被删旧任务不得出现，其余不丢");
        }
    }

    private static List<Long> seqs(JobStore.Page p) {
        return p.items().stream().map(JobView::seq).toList();
    }

    /** 按 id 精确领取（测试辅助）：循环领取直到目标任务出现（受退避影响时调用方先快进）。 */
    private JobService.ClaimResult claimById(TestSupport t, String targetId) {
        for (int i = 0; i < 50; i++) {
            JobService.ClaimResult r = t.service.claimNext("test-worker");
            if (r.claimed() && r.job().id().equals(targetId)) {
                return r;
            }
            if (!r.claimed()) {
                t.clock.fastForward(10_001);
            }
        }
        throw new AssertionError("未能领取到目标任务 " + targetId);
    }
}
