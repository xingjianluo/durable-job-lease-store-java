package com.github.xingjianluo.durablejobleasestorejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.xingjianluo.durablejobleasestorejava.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 HTTP 验证：真实 Spring 容器 + 随机端口，确认错误码与 HTTP 状态经网络层正确传递。
 * 持久化目录由 application-test.properties 隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "job.data-dir=target/test-data/api-it",
                "job.lease-ttl-millis=1000",
                "job.base-backoff-millis=100",
                "job.max-backoff-millis=10000",
                "job.max-attempts=3",
                "job.reaper-interval-millis=60000"
        })
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobStore jobStore;

    @BeforeEach
    void clearStore() {
        // 同一 ApplicationContext 内隔离测试数据：删除全部终态任务，
        // 非终态的残留任务直接清空（测试环境专属目录，仅用于测试隔离）。
        jobStore.allRunningSnapshot().forEach(j -> { });
        new java.util.ArrayList<>(jobStore.allJobIds()).forEach(id -> {
            try {
                jobStore.deleteTerminal(id);
            } catch (Exception ignore) {
                jobStore.forceDeleteForTesting(id);
            }
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> r) {
        return r.getBody();
    }

    @Test
    void fullLifecycleAndDistinctErrorCodesOverHttp() {
        // 创建
        ResponseEntity<Map> created = rest.postForEntity(url("/api/jobs"),
                Map.of("type", "email", "payload", "hello"), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String id = (String) body(created).get("id");
        log.info("HTTP create -> id={} status={}", id, body(created).get("status"));
        assertNotNull(id);

        // 再造一条，保证 limit=1 时有下一页
        String secondId = body(rest.postForEntity(url("/api/jobs"), Map.of("type", "email2"), Map.class)).get("id").toString();

        // 列表第一页
        ResponseEntity<Map> list = rest.getForEntity(url("/api/jobs?limit=1"), Map.class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        String nextCursor = (String) body(list).get("nextCursor");
        log.info("HTTP list -> items={} nextCursor存在={}", ((List<?>) body(list).get("items")).size(), nextCursor != null);
        org.junit.jupiter.api.Assertions.assertNotNull(nextCursor);

        // 篡改游标（翻转签名段字符）-> 400 CURSOR_INVALID
        int flipIdx = nextCursor.length() - 3;
        char c = nextCursor.charAt(flipIdx);
        String tampered = nextCursor.substring(0, flipIdx) + (c == 'A' ? 'B' : 'A') + nextCursor.substring(flipIdx + 1);
        ResponseEntity<Map> badCursor = rest.getForEntity(url("/api/jobs?cursor=" + tampered), Map.class);
        log.info("HTTP 篡改游标 -> status={} body={}", badCursor.getStatusCode(), badCursor.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, badCursor.getStatusCode());
        assertEquals("CURSOR_INVALID", body(badCursor).get("code"));

        // 领取
        ResponseEntity<Map> claim = rest.postForEntity(url("/api/jobs/claim?workerId=w1"), null, Map.class);
        assertEquals(HttpStatus.OK, claim.getStatusCode());
        Map claimBody = body(claim);
        String token = (String) claimBody.get("leaseToken");
        Map claimedJob = (Map) claimBody.get("job");
        log.info("HTTP claim -> claimed={} jobId={} token存在={}", claimBody.get("claimed"), claimedJob.get("id"), token != null);
        assertEquals(id, claimedJob.get("id"));

        // 无 token 完成 -> 409 LEASE_TAKEN_OVER
        ResponseEntity<Map> noToken = rest.postForEntity(
                url("/api/jobs/" + id + "/complete"),
                new HttpEntity<>(Map.of("succeeded", true)), Map.class);
        log.info("HTTP 无token完成 -> status={} code={}", noToken.getStatusCode(), body(noToken).get("code"));
        assertEquals(HttpStatus.CONFLICT, noToken.getStatusCode());
        assertEquals("LEASE_TAKEN_OVER", body(noToken).get("code"));

        // 正确 token 完成 -> 200
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Lease-Token", token);
        ResponseEntity<Map> complete = rest.exchange(
                url("/api/jobs/" + id + "/complete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("succeeded", true, "result", "done"), headers), Map.class);
        assertEquals(HttpStatus.OK, complete.getStatusCode());
        Map completeJob = (Map) body(complete).get("job");
        log.info("HTTP complete -> status={} duplicate={}", completeJob.get("status"), body(complete).get("duplicate"));
        assertEquals("SUCCEEDED", completeJob.get("status"));

        // 重复完成 -> 200 duplicate=true
        ResponseEntity<Map> dup = rest.exchange(
                url("/api/jobs/" + id + "/complete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("succeeded", false, "result", "overwrite"), headers), Map.class);
        assertEquals(HttpStatus.OK, dup.getStatusCode());
        assertEquals(Boolean.TRUE, body(dup).get("duplicate"));
        Map dupJob = (Map) body(dup).get("job");
        Map comp = (Map) dupJob.get("completion");
        log.info("HTTP 重复完成 -> duplicate=true, 首次结果保持={}", comp.get("result"));
        assertEquals("done", comp.get("result"));

        // 终态续租 -> 409 ILLEGAL_TRANSITION
        ResponseEntity<Map> renew = rest.exchange(
                url("/api/jobs/" + id + "/renew"), HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
        log.info("HTTP 终态续租 -> status={} code={}", renew.getStatusCode(), body(renew).get("code"));
        assertEquals(HttpStatus.CONFLICT, renew.getStatusCode());
        assertEquals("ILLEGAL_TRANSITION", body(renew).get("code"));

        // 删除终态 -> 200，再取 -> 404
        ResponseEntity<Map> deleted = rest.exchange(url("/api/jobs/" + id), HttpMethod.DELETE, HttpEntity.EMPTY, Map.class);
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        ResponseEntity<Map> missing = rest.getForEntity(url("/api/jobs/" + id), Map.class);
        log.info("HTTP 删除后查询 -> status={} code={}", missing.getStatusCode(), body(missing).get("code"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("NOT_FOUND", body(missing).get("code"));
        assertTrue(true, "全部断言通过");
    }

    @Test
    void concurrentClaimsOverHttpYieldSingleWinner() throws Exception {
        String a = body(rest.postForEntity(url("/api/jobs"), Map.of("type", "c1"), Map.class)).get("id").toString();
        String b = body(rest.postForEntity(url("/api/jobs"), Map.of("type", "c2"), Map.class)).get("id").toString();

        int threads = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.Set<String> winners = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.concurrent.atomic.AtomicInteger claimedCount = new java.util.concurrent.atomic.AtomicInteger();
        List<Runnable> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String w = "hw" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<Map> r = rest.postForEntity(url("/api/jobs/claim?workerId=" + w), null, Map.class);
                    if (Boolean.TRUE.equals(body(r).get("claimed"))) {
                        Map job = (Map) body(r).get("job");
                        winners.add(job.get("id").toString());
                        claimedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
                return;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        log.info("HTTP 并发: {} 线程抢 2 任务 -> 胜出任务集合={}，成功领取={} 次",
                threads, winners, claimedCount.get());
        // 胜出集合恰好包含本测试的两个任务（集合无重复即说明每个任务只被领取一次）；
        // 成功次数等于集合大小，说明不存在对同一任务的重复成功领取。
        assertEquals(java.util.Set.of(a, b), winners);
        assertEquals(winners.size(), claimedCount.get(), "同一任务不得被重复领取");
    }
}
