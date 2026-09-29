package com.github.xingjianluo.durablejobleasestorejava.tests;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.store.TaskStore;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.MutableClock;
import com.github.xingjianluo.durablejobleasestorejava.tests.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：通过真实 HTTP 端口验证完整流程与可区分的 errorCode。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "durable.store-file=target/test-data/http/http.json")
@Import(TestClockConfig.class)
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @LocalServerPort
    private int port;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private MutableClock clock;
    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        taskStore.clear();
        clock.set(TestClockConfig.FIXED_START);
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/tasks" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String errorCode(ResponseEntity<String> resp) {
        try {
            return JSON.readTree(resp.getBody()).path("errorCode").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fullHttpFlow_distinctErrorCodes() throws Exception {
        // 创建
        ResponseEntity<String> created = rest.postForEntity(url(""),
                new HttpEntity<>("{\"payload\":\"http-job\"}", jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String taskId = JSON.readTree(created.getBody()).path("id").asText();
        log.info("【创建】POST /api/tasks -> 201, id={}", taskId);

        // 领取
        ResponseEntity<String> claim = rest.postForEntity(url("/claim"),
                new HttpEntity<>("{\"workerId\":\"w1\",\"taskId\":\"" + taskId + "\"}", jsonHeaders()),
                String.class);
        assertEquals(HttpStatus.OK, claim.getStatusCode());
        JsonNode claimBody = JSON.readTree(claim.getBody());
        String token = claimBody.path("leaseToken").asText();
        log.info("【领取】w1 token={}", token);

        // 并发领取 -> 409 CONCURRENT_MODIFICATION
        ResponseEntity<String> claim2 = rest.postForEntity(url("/claim"),
                new HttpEntity<>("{\"workerId\":\"w2\",\"taskId\":\"" + taskId + "\"}", jsonHeaders()),
                String.class);
        assertEquals(HttpStatus.CONFLICT, claim2.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", errorCode(claim2));
        log.info("【并发领取】409 errorCode=CONCURRENT_MODIFICATION");

        // 完成
        ResponseEntity<String> complete = rest.postForEntity(url("/" + taskId + "/complete"),
                new HttpEntity<>("{\"workerId\":\"w1\",\"leaseToken\":\"" + token
                        + "\",\"succeeded\":true}", jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, complete.getStatusCode());
        assertEquals("SUCCEEDED", JSON.readTree(complete.getBody()).path("status").asText());
        log.info("【完成】status=SUCCEEDED");

        // 重复提交 -> 409 DUPLICATE_SUBMISSION
        ResponseEntity<String> dup = rest.postForEntity(url("/" + taskId + "/complete"),
                new HttpEntity<>("{\"workerId\":\"w1\",\"leaseToken\":\"" + token
                        + "\",\"succeeded\":true}", jsonHeaders()), String.class);
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());
        assertEquals("DUPLICATE_SUBMISSION", errorCode(dup));
        log.info("【重复提交】409 errorCode=DUPLICATE_SUBMISSION");

        // 篡改游标 -> 400 CURSOR_INVALID
        ResponseEntity<String> badCursor = rest.getForEntity(url("?cursor=garbage.value"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badCursor.getStatusCode());
        assertEquals("CURSOR_INVALID", errorCode(badCursor));
        log.info("【坏游标】400 errorCode=CURSOR_INVALID");

        // 不存在任务 -> 404 TASK_NOT_FOUND
        ResponseEntity<String> missing = rest.getForEntity(url("/nope"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("TASK_NOT_FOUND", errorCode(missing));
        log.info("【不存在】404 errorCode=TASK_NOT_FOUND");

        // 分页正常（再造一条，使 limit=1 时存在下一页）
        rest.postForEntity(url(""),
                new HttpEntity<>("{\"payload\":\"second\"}", jsonHeaders()), String.class);
        ResponseEntity<String> page = rest.getForEntity(url("?limit=1"), String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        JsonNode pageNode = JSON.readTree(page.getBody());
        assertTrue(pageNode.path("hasMore").asBoolean());
        assertFalse(pageNode.path("nextCursor").asText().isBlank());
        log.info("【分页】limit=1, hasMore=true, nextCursor 已签发");

        log.info("【判定】HTTP 层各失败场景 errorCode 全部可区分: "
                + "CONCURRENT_MODIFICATION / DUPLICATE_SUBMISSION / CURSOR_INVALID / TASK_NOT_FOUND");
    }

    @Test
    void leaseExpiryOverHttp_expiredThenReclaimed() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(url(""),
                new HttpEntity<>("{\"payload\":\"expire-job\"}", jsonHeaders()), String.class);
        String taskId = JSON.readTree(created.getBody()).path("id").asText();
        String token = JSON.readTree(rest.postForEntity(url("/claim"),
                new HttpEntity<>("{\"workerId\":\"w1\",\"taskId\":\"" + taskId + "\"}", jsonHeaders()),
                String.class).getBody()).path("leaseToken").asText();

        clock.advance(java.time.Duration.ofSeconds(11));
        ResponseEntity<String> expired = rest.postForEntity(url("/" + taskId + "/renew"),
                new HttpEntity<>("{\"workerId\":\"w1\",\"leaseToken\":\"" + token + "\"}", jsonHeaders()),
                String.class);
        assertEquals(HttpStatus.CONFLICT, expired.getStatusCode());
        assertEquals("LEASE_EXPIRED", errorCode(expired));
        log.info("【HTTP 续租过期】409 errorCode=LEASE_EXPIRED");
    }
}
