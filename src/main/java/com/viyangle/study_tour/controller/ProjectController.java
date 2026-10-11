package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.JoinProjectRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private KnowledgeGraphRecommendService recommendService;

    /** 解析 JSON 形式的请求体；保留默认实例，便于不经过 Spring 构造控制器时也能工作。 */
    @Autowired
    private ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    @OperationLog(value = "分页获取项目", type = "PROJECT_QUERY")
    public Result getAllProjects(@RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize,
                                 @RequestParam Long accountId,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "false") Boolean withExplanation) {
        log.info("分页获取项目, accountId={}, keyword={}, pageNum={}, pageSize={}, withExplanation={}",
                accountId, keyword, pageNum, pageSize, withExplanation);
        if (Boolean.TRUE.equals(withExplanation)) {
            int limit = pageNum * pageSize;
            return Result.success(recommendService.recommendWithExplanation(accountId, keyword, limit));
        }
        return Result.success(projectService.getPagedProjectsByPreference(accountId, pageNum, pageSize, keyword));
    }

    @GetMapping("/filter")
    @OperationLog(value = "复合筛选项目", type = "PROJECT_QUERY")
    public Result filterProjects(@RequestParam(required = false) Long accountId,
                                 @RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String regionCode,
                                 @RequestParam(required = false) String tag,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateFrom,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateTo,
                                 @RequestParam(required = false) Long ownerAccountId,
                                 @RequestParam(required = false) Long leaderAccountId,
                                 @RequestParam(required = false) Boolean hasLeader,
                                 @RequestParam(required = false) Boolean onlyAvailable) {
        log.info("复合筛选项目, accountId={}, keyword={}, regionCode={}, tag={}, status={}, pageNum={}, pageSize={}",
                accountId, keyword, regionCode, tag, status, pageNum, pageSize);
        return Result.success(projectService.filterProjects(
                accountId,
                pageNum,
                pageSize,
                keyword,
                regionCode,
                tag,
                status,
                departureDateFrom,
                departureDateTo,
                ownerAccountId,
                leaderAccountId,
                hasLeader,
                onlyAvailable
        ));
    }

    @GetMapping("/available")
    @RequireRole({"LEADER"})
    @OperationLog(value = "查询可接项目", type = "PROJECT_QUERY")
    public Result getAvailableProjects(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询可接项目: accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        return Result.success(projectService.getAvailableProjectPage(accountId, pageNum, pageSize));
    }

    @GetMapping("/mine")
    @OperationLog(value = "查询我的项目", type = "PROJECT_QUERY")
    public Result getMyProjects(@RequestParam(defaultValue = "ALL") String relation,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String stage,
                                @RequestParam(required = false) List<String> statuses,
                                @RequestParam(defaultValue = "1") Integer pageNum,
                                @RequestParam(defaultValue = "10") Integer pageSize) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询我的项目: accountId={}, relation={}, status={}, stage={}, statuses={}",
                accountId, relation, status, stage, statuses);
        return Result.success(projectService.getMyProjects(
                accountId,
                relation,
                status,
                stage,
                statuses,
                pageNum,
                pageSize
        ));
    }

    @GetMapping("/{id}")
    @OperationLog(value = "获取项目详情", type = "PROJECT_QUERY")
    public Result getProjectById(@PathVariable Long id) {
        log.info("获取项目: {}", id);
        return Result.success(projectService.getProjectDetail(
                SecurityContextUtil.currentAccountId(),
                id
        ));
    }

    @GetMapping("/{id}/members")
    @OperationLog(value = "获取项目成员", type = "PROJECT_QUERY")
    public Result getProjectMembers(@PathVariable Long id) {
        log.info("获取项目成员: {}", id);
        return Result.success(projectService.getProjectMembers(id));
    }

    @PostMapping
    @OperationLog(value = "创建项目", type = "PROJECT_CREATE")
    @RequireRole({"USER"})
    public Result createProject(@RequestBody Project project) {
        log.info("创建项目");
        return Result.success(projectService.createProject(project));
    }

    @PostMapping("/{id}/join")
    @OperationLog(value = "加入项目", type = "PROJECT_JOIN")
    @RequireRole({"USER"})
    public Result joinProject(@PathVariable Long id, @RequestBody JoinProjectRequest request) {
        log.info("加入项目: {}, representedCount={}", id,
                request == null ? null : request.getRepresentedCount());
        projectService.joinProject(
                id,
                SecurityContextUtil.currentAccountId(),
                request == null ? null : request.getRepresentedCount()
        );
        return Result.success();
    }

    @PostMapping("/{id}/quit")
    @OperationLog(value = "退出项目", type = "PROJECT_QUIT")
    @RequireRole({"USER"})
    public Result quitProject(@PathVariable Long id) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("退出项目: projectId={}, accountId={}", id, accountId);
        projectService.quitProject(id, accountId);
        return Result.success();
    }

    @PostMapping("/{id}/leader")
    @OperationLog(value = "指定项目领队", type = "PROJECT_UPDATE")
    @RequireRole({"USER"})
    public Result leaderJoinProject(@PathVariable Long id, @RequestBody Project project) {
        log.info("项目组长加入项目: {}", id);
        project.setId(id);
        projectService.leaderJoinProject(project, SecurityContextUtil.currentAccountId());
        return Result.success();
    }

    @PostMapping("/{id}/accept")
    @OperationLog(value = "领队接单", type = "PROJECT_UPDATE")
    @RequireRole({"LEADER"})
    public Result acceptProject(@PathVariable Long id) {
        log.info("领队接单: projectId={}", id);
        projectService.acceptProject(id, SecurityContextUtil.currentAccountId());
        return Result.success();
    }

    @PostMapping("/{id}/abandon")
    @OperationLog(value = "领队放弃带队并重新开放项目", type = "PROJECT_UPDATE")
    @RequireRole({"LEADER"})
    public Result abandonProject(@PathVariable Long id,
                                 @RequestParam(required = false) String reason,
                                 @RequestBody(required = false) String rawBody) {
        Long accountId = SecurityContextUtil.currentAccountId();
        // 兼容三种传法：查询串 ?reason=xxx、JSON 体 {"reason":"xxx"}、表单编码 reason=xxx
        // （表单参数由 @RequestParam 解析；用 String 接收请求体可避免表单编码被判为不支持的媒体类型）。
        // 三者都缺失时按 400 拒绝。
        String effectiveReason = reason != null ? reason : extractReasonFromJsonBody(rawBody);
        log.info("领队放弃带队: projectId={}, leaderAccountId={}, reasonProvided={}",
                id, accountId, effectiveReason != null);
        projectService.abandonProject(id, accountId, effectiveReason);
        return Result.success();
    }

    /**
     * 查询项目的放弃带队审计记录。
     *
     * <p>返回项目、领队、原因、放弃前状态与操作时间；仅项目发布者、接单领队（含已放弃的领队）与 ADMIN 可查询。</p>
     */
    @GetMapping("/{id}/abandon-records")
    @OperationLog(value = "查询放弃带队记录", type = "PROJECT_QUERY")
    public Result getAbandonRecords(@PathVariable Long id) {
        log.info("查询放弃带队记录: projectId={}", id);
        return Result.success(projectService.getAbandonRecords(
                id,
                SecurityContextUtil.currentAccountId(),
                SecurityContextUtil.currentRole()
        ));
    }

    private String extractReasonFromJsonBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        String trimmed = rawBody.trim();
        if (!trimmed.startsWith("{")) {
            // 非 JSON 的请求体不做猜测，交给 service 按“原因缺失”拒绝。
            return null;
        }
        try {
            JsonNode reason = objectMapper.readTree(trimmed).get("reason");
            return reason == null || reason.isNull() ? null : reason.asText();
        } catch (Exception e) {
            log.warn("解析放弃原因请求体失败: {}", e.getMessage());
            return null;
        }
    }

    @PostMapping("/{id}/status")
    @OperationLog(value = "更新项目状态", type = "PROJECT_UPDATE")
    @RequireRole({"USER", "LEADER", "ADMIN"})
    public Result updateProjectStatus(@PathVariable Long id, @RequestParam String status) {
        log.info("更新项目状态: projectId={}, status={}", id, status);
        projectService.transitionProjectStatus(
                id,
                status,
                SecurityContextUtil.currentAccountId(),
                SecurityContextUtil.currentRole()
        );
        return Result.success();
    }

}
