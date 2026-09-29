package com.github.xingjianluo.durablejobleasestorejava.api;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobView;
import com.github.xingjianluo.durablejobleasestorejava.service.JobService;
import com.github.xingjianluo.durablejobleasestorejava.store.JobStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务 REST 接口。租约 token 通过响应返回、由调用方在后续请求头 X-Lease-Token 携带。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    public record CreateRequest(String type, String payload) {}

    @PostMapping
    public JobView create(@RequestBody CreateRequest req) {
        return jobs.createJob(req == null ? null : req.type(), req == null ? null : req.payload());
    }

    public record ListResponse(List<JobView> items, String nextCursor, boolean anchorFound) {}

    @GetMapping
    public ListResponse list(@RequestParam(defaultValue = "20") int limit,
                             @RequestParam(required = false) String cursor) {
        JobStore.Page page = jobs.listJobs(limit, cursor);
        return new ListResponse(page.items(), page.nextCursor(), page.anchorFound());
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable String id) {
        return jobs.getJob(id);
    }

    public record ClaimResponse(JobView job, String leaseToken, boolean claimed) {}

    @PostMapping("/claim")
    public ClaimResponse claim(@RequestParam(defaultValue = "anonymous-worker") String workerId) {
        JobService.ClaimResult r = jobs.claimNext(workerId);
        return new ClaimResponse(r.job(), r.leaseToken(), r.claimed());
    }

    public record CompleteRequest(boolean succeeded, String result) {}

    public record CompleteResponse(JobView job, boolean duplicate) {}

    @PostMapping("/{id}/complete")
    public CompleteResponse complete(@PathVariable String id,
                                     @RequestHeader(value = "X-Lease-Token", required = false) String leaseToken,
                                     @RequestBody CompleteRequest req) {
        JobService.CompleteResult r = jobs.complete(id, leaseToken,
                req != null && req.succeeded(), req == null ? null : req.result());
        return new CompleteResponse(r.job(), r.duplicate());
    }

    @PostMapping("/{id}/renew")
    public JobView renew(@PathVariable String id,
                         @RequestHeader(value = "X-Lease-Token", required = false) String leaseToken) {
        return jobs.renew(id, leaseToken);
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable String id) {
        return jobs.cancel(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        jobs.deleteTerminal(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
