package com.viyangle.study_tour.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.RunView;
import com.viyangle.study_tour.agent.exception.AgentApiException;
import com.viyangle.study_tour.agent.exception.AgentValidationException;
import com.viyangle.study_tour.agent.service.AgentRunExecutor;
import com.viyangle.study_tour.agent.service.AgentRunStore;
import com.viyangle.study_tour.agent.service.AgentRunViews;
import com.viyangle.study_tour.agent.service.PlanRequestValidator;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 路线规划 Agent 接口。
 *
 * <p>响应是直接 JSON 对象，不包裹在 {@code {code,msg,data}} 中；错误响应为
 * {@code {"error": "..."}}，参数校验失败为 HTTP 422 + FastAPI 风格 {@code {"detail": [...]}}。
 * 账号身份一律取自登录 token，请求体中的 {@code accountId} 只用于一致性校验。</p>
 */
@Slf4j
@RestController
@RequestMapping("/routes/agent/runs")
public class AgentRunController {

    private final AgentRunStore store;
    private final AgentRunExecutor executor;
    private final PlanRequestValidator validator;
    private final ObjectMapper objectMapper;

    public AgentRunController(AgentRunStore store,
                              AgentRunExecutor executor,
                              PlanRequestValidator validator,
                              ObjectMapper objectMapper) {
        this.store = store;
        this.executor = executor;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /** 创建一轮异步规划；HTTP 202 仅表示任务已接受。 */
    @PostMapping
    public ResponseEntity<RunView> start(@RequestBody(required = false) PlanRequest request) {
        Long accountId = requireAccount();
        List<Map<String, Object>> errors = validator.validate(request);
        if (!errors.isEmpty()) {
            throw new AgentValidationException(errors);
        }
        if (!accountId.equals(request.getAccountId())) {
            throw AgentApiException.forbidden("账号与登录身份不一致");
        }
        String role = SecurityContextUtil.currentRole();
        AgentRunStore.CreateOutcome outcome =
                store.createOrReuse(request, accountId, role, fingerprint(request));
        if (outcome.created()) {
            executor.submit(outcome.entry(), request, accountId, role);
        } else {
            log.info("幂等复用已有规划任务, requestId={}, runId={}", request.getRequestId(),
                    outcome.entry().getRunId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AgentRunViews.toView(outcome.entry()));
    }

    /** 查询阶段、事件与结果；HTTP 200 不等于任务成功，需要检查 status。 */
    @GetMapping("/{runId}")
    public RunView status(@PathVariable String runId) {
        Long accountId = requireAccount();
        AgentRunStore.RunEntry entry = store.findForAccount(runId, accountId);
        if (entry == null) {
            throw AgentApiException.notFound("任务不存在");
        }
        return AgentRunViews.toView(entry);
    }

    /** 取消规划；已完成/失败/取消的任务返回现有终态。 */
    @DeleteMapping("/{runId}")
    public RunView cancel(@PathVariable String runId) {
        Long accountId = requireAccount();
        AgentRunStore.RunEntry entry = store.findForAccount(runId, accountId);
        if (entry == null) {
            throw AgentApiException.notFound("任务不存在");
        }
        if (entry.isActive()) {
            log.info("取消规划任务, runId={}, accountId={}", runId, accountId);
            store.requestCancel(entry);
        }
        return AgentRunViews.toView(entry);
    }

    private Long requireAccount() {
        Long accountId = SecurityContextUtil.currentAccountId();
        if (accountId == null) {
            throw new UnauthorizedException("未登录或token无效");
        }
        return accountId;
    }

    private String fingerprint(PlanRequest request) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            log.warn("计算请求指纹失败，退化为字段拼接: {}", e.getMessage());
            return request.getRequestId() + "|" + request.getMemoryId() + "|" + request.getMessage();
        }
    }
}
