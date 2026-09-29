package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.service.PageResult;
import com.github.xingjianluo.durablejobleasestorejava.service.TaskService;
import com.github.xingjianluo.durablejobleasestorejava.store.TaskStore;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.MutableClock;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：稳定顺序游标分页。
 * - 正常翻页：全部条目恰好出现一次（不重不漏），顺序稳定；
 * - 游标被篡改：CURSOR_INVALID；
 * - 游标过期：CURSOR_EXPIRED；
 * - 游标锚点被删：CURSOR_ANCHOR_DELETED；三类可区分；
 * - 翻页期间新增/删除：窗口内不重复、不漏读。
 */
@SpringBootTest(properties = {"durable.store-file=target/test-data/cursor/cursor.json", "durable.cursor-ttl=10m"})
@Import(TestClockConfig.class)
class CursorPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(CursorPaginationTest.class);

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private MutableClock clock;

    private ApiException expectCode(ErrorCode expected, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getCode());
        return ex;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        taskStore.clear();
        clock.set(com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig.FIXED_START);
    }

    @Test
    void pagination_isStableAndComplete() {
        String prefix = "page-stable-";
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // 每次推进 1ms，保证 ID 时间前缀严格有序
            clock.advance(Duration.ofMillis(1));
            created.add(taskService.create(prefix + i).getId());
        }
        log.info("【输入】创建 7 个任务，pageSize=3");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pageNo = 0;
        while (true) {
            PageResult page = taskService.list(cursor, 3);
            pageNo++;
            for (Object o : page.items()) {
                seen.add(((JobTask) o).getId());
            }
            log.info("【第{}页】size={}, hasMore={}, nextCursor={}",
                    pageNo, page.items().size(), page.hasMore(),
                    page.nextCursor() == null ? "null" : page.nextCursor().substring(0, 24) + "...");
            if (!page.hasMore()) {
                assertNull(page.nextCursor());
                break;
            }
            assertNotNull(page.nextCursor());
            cursor = page.nextCursor();
        }
        assertEquals(7, seen.size());
        assertEquals(new HashSet<>(created), new HashSet<>(seen), "全部任务恰好出现一次");
        List<String> sorted = seen.stream().sorted().toList();
        assertEquals(sorted, seen, "顺序必须是稳定的 ID 升序");
        log.info("【判定】7 个任务分 {} 页取完，无重复无遗漏，顺序稳定", pageNo);
    }

    @Test
    void tampered_expired_anchorDeleted_giveDistinctErrors() {
        String prefix = "page-cursor-";
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMillis(1));
            taskService.create(prefix + i);
        }
        PageResult first = taskService.list(null, 2);
        String cursor = first.nextCursor();
        String anchor = ((JobTask) first.items().get(1)).getId();
        log.info("【输入】首页末条 anchor={}, cursor={}", anchor, cursor.substring(0, 24) + "...");

        // 1) 篡改：翻转签名段的一个字符
        char last = cursor.charAt(cursor.length() - 1);
        String flipped = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');
        ApiException tampered = expectCode(ErrorCode.CURSOR_INVALID,
                () -> taskService.list(flipped, 2));
        log.info("【篡改签名】code={}, message={}", tampered.getCode(), tampered.getMessage());

        // 1b) 篡改：伪造结构
        expectCode(ErrorCode.CURSOR_INVALID, () -> taskService.list("not-a-cursor", 2));
        expectCode(ErrorCode.CURSOR_INVALID, () -> taskService.list("aGVsbG8.broken-sig", 2));
        String fakePayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((anchor + "|0").getBytes(StandardCharsets.UTF_8));
        expectCode(ErrorCode.CURSOR_INVALID, () -> taskService.list(fakePayload + ".AAAA", 2));
        log.info("【格式/伪造游标】均为 CURSOR_INVALID");

        // 2) 过期：时钟推进超过 10m
        clock.advance(Duration.ofMinutes(11));
        ApiException expired = expectCode(ErrorCode.CURSOR_EXPIRED,
                () -> taskService.list(cursor, 2));
        log.info("【游标过期】code={}, message={}", expired.getCode(), expired.getMessage());

        // 回到有效期内，删除锚点记录
        clock.advance(Duration.ofMinutes(-11));
        assertTrue(taskService instanceof com.github.xingjianluo.durablejobleasestorejava.service.TaskService);
        taskService.delete(anchor);
        log.info("【删除锚点】anchor={} 已从存储移除", anchor);
        ApiException anchorDeleted = expectCode(ErrorCode.CURSOR_ANCHOR_DELETED,
                () -> taskService.list(cursor, 2));
        log.info("【锚点删除】code={}, message={}", anchorDeleted.getCode(), anchorDeleted.getMessage());

        // 删除其他（非锚点）记录后用原游标继续：不报错，窗口内不重不漏
        PageResult p2 = taskService.list(null, 2);
        String c2 = p2.nextCursor();
        String anchor2 = ((JobTask) p2.items().get(1)).getId();
        String beforeAnchor = ((JobTask) p2.items().get(0)).getId();
        assertNotNull(c2);
        assertNotNull(taskService.get(anchor2));
        taskService.delete(beforeAnchor); // 删除上一条（锚点之前），锚点仍在
        PageResult next = taskService.list(c2, 2);
        assertFalse(next.items().isEmpty(), "锚点仍存在时游标必须可用");
        for (Object o : next.items()) {
            assertNotEquals(beforeAnchor, ((JobTask) o).getId(), "被删条目不应出现");
        }
        assertNotNull(taskService.get(anchor2), "锚点记录未被删除");
        log.info("【判定】非锚点删除不影响游标有效性（正常返回且不含被删条目）；三类游标错误码互不相同且可区分: "
                + "CURSOR_INVALID / CURSOR_EXPIRED / CURSOR_ANCHOR_DELETED");
    }

    @Test
    void concurrentInsertAndDeleteDuringPagination_noDupNoMiss() {
        String prefix = "page-race-";
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMillis(1));
            base.add(taskService.create(prefix + i).getId());
        }

        // 第 1 页（取 2 条）
        PageResult p1 = taskService.list(null, 2);
        String c1 = p1.nextCursor();
        Set<String> page1Ids = collect(p1);
        log.info("【翻页中】第1页={}", page1Ids);

        // 翻页期间新增 3 条（ID 更大，落在后续页尾部；不影响当前窗口）
        List<String> added = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(1));
            added.add(taskService.create(prefix + "new-" + i).getId());
        }
        // 删除“未来页”的一条记录（base 中第 3 条），不应在任何页再出现
        String deletedFuture = base.get(2);
        taskService.delete(deletedFuture);
        log.info("【翻页中】新增={}, 删除未来页条目={}", added.size(), deletedFuture);

        // 从游标继续取完所有页
        Set<String> all = new HashSet<>(page1Ids);
        String cursor = c1;
        int guard = 0;
        while (cursor != null && guard++ < 20) {
            PageResult p = taskService.list(cursor, 2);
            Set<String> ids = collect(p);
            assertTrue(Collections_disjoint(all, ids), "跨页出现重复条目: 交集=" + intersection(all, ids));
            all.addAll(ids);
            assertFalse(ids.contains(deletedFuture), "被删除条目出现在结果中");
            cursor = p.nextCursor();
        }
        // 期望：5 基础 - 1 删除 + 3 新增 = 7
        Set<String> expected = new HashSet<>(base);
        expected.remove(deletedFuture);
        expected.addAll(added);
        assertEquals(expected, all, "翻页期间增删后仍不重不漏");
        log.info("【判定】翻页期间新增 3 条/删除 1 条，最终 {} 条全部读到，无重复", all.size());
    }

    private static Set<String> collect(PageResult p) {
        Set<String> ids = new HashSet<>();
        for (Object o : p.items()) {
            ids.add(((JobTask) o).getId());
        }
        return ids;
    }

    private static boolean Collections_disjoint(Set<String> a, Set<String> b) {
        for (String s : b) {
            if (a.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> r = new HashSet<>(b);
        r.retainAll(a);
        return r;
    }
}
