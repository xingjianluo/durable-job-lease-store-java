package com.github.xingjianluo.durablejobleasestorejava.web;

import com.github.xingjianluo.durablejobleasestorejava.domain.JobTask;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.service.PageResult;
import com.github.xingjianluo.durablejobleasestorejava.service.TaskService;
import com.github.xingjianluo.durablejobleasestorejava.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务 REST 接口：
 * <pre>
 * POST   /api/tasks             创建
 * GET    /api/tasks             游标分页列表 (?cursor=&limit=)
 * GET    /api/tasks/{id}        详情
 * POST   /api/tasks/claim       领取指定任务 {workerId, taskId}
 * POST   /api/tasks/claim-any   领取任意可派发任务 {workerId}
 * POST   /api/tasks/{id}/renew  续租 {workerId, leaseToken}
 * POST   /api/tasks/{id}/complete 提交结果 {workerId, leaseToken, succeeded, errorMessage}
 * POST   /api/tasks/{id}/cancel 取消
 * DELETE /api/tasks/{id}        删除（管理/演示）
 * </pre>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobTask create(@RequestBody CreateTaskRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "请求体为空");
        }
        return taskService.create(request.payload());
    }

    @GetMapping
    public PageResponse list(@RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "20") int limit) {
        PageResult page = taskService.list(cursor, limit);
        return new PageResponse(page.items(), page.nextCursor(), page.hasMore());
    }

    @GetMapping("/{id}")
    public JobTask get(@PathVariable String id) {
        return taskService.get(id);
    }

    @PostMapping("/claim")
    public JobTask claim(@RequestBody ClaimRequest request) {
        require(request != null && request.taskId() != null, "taskId 不能为空");
        require(request.workerId() != null, "workerId 不能为空");
        return taskService.claim(request.workerId(), request.taskId());
    }

    @PostMapping("/claim-any")
    public ResponseEntity<JobTask> claimAny(@RequestBody Map<String, String> body) {
        String workerId = body == null ? null : body.get("workerId");
        require(workerId != null && !workerId.isBlank(), "workerId 不能为空");
        JobTask task = taskService.claimAny(workerId);
        return task == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(task);
    }

    @PostMapping("/{id}/renew")
    public JobTask renew(@PathVariable String id, @RequestBody RenewRequest request) {
        require(request != null, "请求体为空");
        return taskService.renew(id, request.workerId(), request.leaseToken());
    }

    @PostMapping("/{id}/complete")
    public JobTask complete(@PathVariable String id, @RequestBody CompleteRequest request) {
        require(request != null, "请求体为空");
        require(request.workerId() != null, "workerId 不能为空");
        require(request.leaseToken() != null, "leaseToken 不能为空");
        boolean succeeded = request.succeeded() == null || request.succeeded();
        return taskService.complete(id, request.workerId(), request.leaseToken(),
                succeeded, request.errorMessage());
    }

    @PostMapping("/{id}/cancel")
    public JobTask cancel(@PathVariable String id) {
        return taskService.cancel(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        taskService.delete(id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, message);
        }
    }
}
