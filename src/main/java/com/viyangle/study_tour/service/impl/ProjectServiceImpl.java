package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ProjectMemberMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Transactional
    @Override
    public void createProject(Project project) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        if (currentAccountId != null) {
            project.setOwnerAccountId(currentAccountId);
        }
        if (project.getOwnerAccountId() == null) {
            throw new UnauthorizedException("未认证用户");
        }

        projectMapper.insert(project);
        projectMemberMapper.insert(new ProjectMember(null, project.getId(), project.getOwnerAccountId(), "JOINED", LocalDateTime.now()));
    }

    @Override
    public List<Project> getAllProjects() {
        return projectMapper.selectAll();
    }

    @Override
    public List<Project> getPagedProjectsByPreference(Long accountId, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

        String regionCode = null;
        List<String> preferredTagNames = Collections.emptyList();
        if (accountId != null) {
            Account account = accountMapper.selectById(accountId);
            if (account != null) {
                regionCode = account.getRegionCode();
            }

            List<AccountTagPref> tagPrefs = accountTagPrefMapper.selectByAccountId(accountId);
            if (tagPrefs != null && !tagPrefs.isEmpty()) {
                Set<Long> prefTagIds = tagPrefs.stream()
                        .map(AccountTagPref::getTagId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (!prefTagIds.isEmpty()) {
                    Map<Long, String> tagIdNameMap = tagMapper.selectAll().stream()
                            .collect(Collectors.toMap(Tag::getId, Tag::getName));

                    preferredTagNames = prefTagIds.stream()
                            .map(tagIdNameMap::get)
                            .filter(Objects::nonNull)
                            .filter(name -> !name.isBlank())
                            .collect(Collectors.toList());
                }
            }
        }

        PageHelper.startPage(page, size);
        return projectMapper.selectByPreference(preferredTagNames, regionCode);
    }

    @Override
    public void joinProject(Long id, Long accountId) {
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }

        ProjectMember existing = projectMemberMapper.selectByProjectIdAndAccountId(id, accountId);
        if (existing != null) {
            throw new ForbiddenException("已加入该项目, projectId=" + id + ", accountId=" + accountId);
        }

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
    public void leaderJoinProject(Project project, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("项目ID不能为空");
        }

        Project existingProject = projectMapper.selectById(project.getId());
        if (existingProject == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + project.getId());
        }
        if (!currentAccountId.equals(existingProject.getOwnerAccountId())) {
            throw new ForbiddenException("仅项目拥有者可修改项目领队");
        }

        projectMapper.updateById(project);
    }
}