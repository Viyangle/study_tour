package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;

import java.util.List;

public interface ProjectService {
    void createProject(Project project);

    List<Project> getAllProjects();

    List<Project> getPagedProjectsByPreference(Long accountId, Integer pageNum, Integer pageSize);

    void joinProject(Long id, Long accountId);

    Project getProjectById(Long id);

    List<ProjectMember> getProjectMembers(Long id);

    void leaderJoinProject(Project project, Long currentAccountId);
}
