package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ProjectMemberMapper;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectServiceImpl implements ProjectService {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;
    @Transactional
    @Override
    public void createProject(Project project) {
        projectMapper.insert(project);
        projectMemberMapper.insert(new ProjectMember(null, project.getId(), project.getOwnerAccountId(), "JOINED", LocalDateTime.now()));
    }

    @Override
    public List<Project> getAllProjects() {
        return projectMapper.selectAll();
    }

    @Override
    public void joinProject(Long id, Long accountId) {
        projectMemberMapper.insert(new ProjectMember(null, id, accountId, "JOINED", LocalDateTime.now()));
    }

    @Override
    public Project getProjectById(Long id) {
        return projectMapper.selectById(id);
    }

    @Override
    public List<ProjectMember> getProjectMembers(Long id) {
        return projectMemberMapper.selectByProjectId(id);
    }

    @Override
    public void leaderJoinProject(Project project) {
        projectMapper.updateById(project);
    }
}
