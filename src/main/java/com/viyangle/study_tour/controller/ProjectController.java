package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;
    @GetMapping
    public Result getAllProjects() {
        log.info("获取所有项目");
        return Result.success(projectService.getAllProjects());
    }

    //TODO: 分页
    @GetMapping("/{id}")
    public Result getProjectById(@PathVariable Long id) {
        log.info("获取项目: {}", id);
        return Result.success(projectService.getProjectById(id));
    }
    @GetMapping("/{id}/members")
    public Result getProjectMembers(@PathVariable Long id) {
        log.info("获取项目成员: {}", id);
        return Result.success(projectService.getProjectMembers(id));
    }
    @PostMapping
    public Result createProject(@RequestBody Project project) {
        log.info("创建项目");
        projectService.createProject(project);
        return Result.success();
    }
    @PostMapping("/{id}/join")
    public Result joinProject(@PathVariable Long id, @RequestBody ProjectMember projectMember) {
        log.info("加入项目: {}", id);
        projectService.joinProject(id, projectMember.getAccountId());
        return Result.success();
    }
    @PostMapping("/{id}/leader")
    public Result leaderJoinProject(@PathVariable Long id, @RequestBody Project project) {
        log.info("项目组长加入项目: {}", id);
        project.setId(id);
        projectService.leaderJoinProject(project);
        return Result.success();
    }
}
