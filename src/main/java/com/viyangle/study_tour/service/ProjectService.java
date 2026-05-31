package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;

import java.time.LocalDate;
import java.util.List;

public interface ProjectService {
    void createProject(Project project);

    List<Project> getAllProjects();

    List<Project> getPagedProjectsByPreference(Long accountId, Integer pageNum, Integer pageSize);

    List<Project> filterProjects(Long accountId,
                                 Integer pageNum,
                                 Integer pageSize,
                                 String keyword,
                                 String regionCode,
                                 String tag,
                                 String status,
                                 LocalDate departureDateFrom,
                                 LocalDate departureDateTo,
                                 Long ownerAccountId,
                                 Long leaderAccountId,
                                 Boolean hasLeader,
                                 Boolean onlyAvailable);

    void joinProject(Long id, Long accountId);

    Project getProjectById(Long id);

    List<ProjectMember> getProjectMembers(Long id);

    void acceptProject(Long id, Long leaderAccountId);

    void leaderJoinProject(Project project, Long currentAccountId);

    void transitionProjectStatus(Long id, String targetStatus, Long currentAccountId, String currentRole);
}
