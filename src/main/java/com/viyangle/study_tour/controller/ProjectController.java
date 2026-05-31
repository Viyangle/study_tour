package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.Result;
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

@Slf4j
@RestController
@RequestMapping("/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @GetMapping
    @OperationLog(value = "分页获取项目", type = "PROJECT_QUERY")
    public Result getAllProjects(@RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize,
                                 @RequestParam Long accountId) {
        log.info("分页获取项目, accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        return Result.success(projectService.getPagedProjectsByPreference(accountId, pageNum, pageSize));
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

    @GetMapping("/{id}")
    @OperationLog(value = "获取项目详情", type = "PROJECT_QUERY")
    public Result getProjectById(@PathVariable Long id) {
        log.info("获取项目: {}", id);
        return Result.success(projectService.getProjectById(id));
    }

    @GetMapping("/{id}/members")
    @OperationLog(value = "获取项目成员", type = "PROJECT_QUERY")
    public Result getProjectMembers(@PathVariable Long id) {
        log.info("获取项目成员: {}", id);
        return Result.success(projectService.getProjectMembers(id));
    }

    @PostMapping
    @OperationLog(value = "创建项目", type = "PROJECT_CREATE")
    @RequireRole({"USER", "LEADER"})
    public Result createProject(@RequestBody Project project) {
        log.info("创建项目");
        projectService.createProject(project);
        return Result.success();
    }

    @PostMapping("/{id}/join")
    @OperationLog(value = "加入项目", type = "PROJECT_JOIN")
    @RequireRole({"USER"})
    public Result joinProject(@PathVariable Long id) {
        log.info("加入项目: {}", id);
        projectService.joinProject(id, SecurityContextUtil.currentAccountId());
        return Result.success();
    }

    @PostMapping("/{id}/leader")
    @OperationLog(value = "指定项目领队", type = "PROJECT_UPDATE")
    @RequireRole({"USER", "LEADER"})
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
