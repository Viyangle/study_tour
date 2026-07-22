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
import com.viyangle.study_tour.pojo.ProjectStatus;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_LEADER = "LEADER";

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

    @Autowired
    private KnowledgeGraphRecommendService kgRecommendService;

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

        normalizeInitialProjectStatus(project);
        validateLeaderAccount(project.getLeaderAccountId());
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

        // 优先使用知识图谱推荐
        if (kgRecommendService != null) {
            try {
                List<Project> kgResult = kgRecommendService.recommendProjects(accountId, page * size);
                if (kgResult != null && !kgResult.isEmpty()) {
                    // 手动分页
                    int fromIndex = (page - 1) * size;
                    if (fromIndex >= kgResult.size()) {
                        return List.of();
                    }
                    int toIndex = Math.min(fromIndex + size, kgResult.size());
                    return kgResult.subList(fromIndex, toIndex);
                }
            } catch (Exception e) {
                // KG 推荐失败，降级到 SQL 方式
            }
        }

        // 降级：原有 SQL 加权排序
        ProjectPreference preference = resolveProjectPreference(accountId);
        PageHelper.startPage(page, size);
        return projectMapper.selectByPreference(preference.preferredTagNames(), preference.regionCode());
    }

    @Override
    public List<Project> filterProjects(Long accountId,
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
                                        Boolean onlyAvailable) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

        ProjectPreference preference = resolveProjectPreference(accountId);
        String filterRegionCode = trimToNull(regionCode);
        String filterTag = trimToNull(tag);
        String sortRegionCode = filterRegionCode == null ? preference.regionCode() : filterRegionCode;
        List<String> preferredTags = filterTag == null ? preference.preferredTagNames() : List.of(filterTag);
        String normalizedStatus = normalizeStatus(status);

        if (departureDateFrom != null && departureDateTo != null && departureDateFrom.isAfter(departureDateTo)) {
            throw new IllegalArgumentException("departureDateFrom不能晚于departureDateTo");
        }

        PageHelper.startPage(page, size);
        return projectMapper.selectByCompositeFilter(
                preferredTags,
                sortRegionCode,
                trimToNull(keyword),
                filterRegionCode,
                filterTag,
                normalizedStatus,
                departureDateFrom,
                departureDateTo,
                ownerAccountId,
                leaderAccountId,
                hasLeader,
                onlyAvailable
        );
    }

    @Transactional
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

        int affected = projectMapper.casIncrementCurrentMembers(id);
        if (affected == 0) {
            throw new ForbiddenException("项目已满员, projectId=" + id);
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

    @Transactional
    @Override
    public void acceptProject(Long id, Long leaderAccountId) {
        if (leaderAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        validateLeaderAccount(leaderAccountId);

        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }

        if (ProjectStatus.CONFIRMED.name().equals(project.getStatus())
                && leaderAccountId.equals(project.getLeaderAccountId())) {
            return;
        }

        ProjectStatus currentStatus = ProjectStatus.from(project.getStatus());
        currentStatus.assertCanTransitionTo(ProjectStatus.CONFIRMED);

        int affected = projectMapper.casAcceptProject(id, leaderAccountId);
        if (affected == 0) {
            Project latest = projectMapper.selectById(id);
            if (latest != null && latest.getLeaderAccountId() != null) {
                throw new ForbiddenException("项目已有领队接单, projectId=" + id);
            }
            throw new ForbiddenException("接单失败，项目状态已变更, projectId=" + id);
        }
    }

    @Transactional
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
        if (project.getLeaderAccountId() == null) {
            throw new IllegalArgumentException("领队账号ID不能为空");
        }

        validateLeaderAccount(project.getLeaderAccountId());

        ProjectStatus currentStatus = ProjectStatus.from(existingProject.getStatus());
        ProjectStatus targetStatus = ProjectStatus.CONFIRMED;
        if (currentStatus == ProjectStatus.CONFIRMED
                && project.getLeaderAccountId().equals(existingProject.getLeaderAccountId())) {
            return;
        }
        currentStatus.assertCanTransitionTo(targetStatus);

        int affected = projectMapper.casAcceptProject(project.getId(), project.getLeaderAccountId());
        if (affected == 0) {
            Project latest = projectMapper.selectById(project.getId());
            if (latest != null && latest.getLeaderAccountId() != null) {
                throw new ForbiddenException("项目已有领队接单, projectId=" + project.getId());
            }
            throw new ForbiddenException("指定领队失败，项目状态已变更, projectId=" + project.getId());
        }
    }

    @Transactional
    @Override
    public void transitionProjectStatus(Long id, String targetStatus, Long currentAccountId, String currentRole) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }

        ensureCanOperateProject(project, currentAccountId, currentRole);

        ProjectStatus current = ProjectStatus.from(project.getStatus());
        ProjectStatus target = ProjectStatus.from(targetStatus);
        if (current == target) {
            return;
        }

        current.assertCanTransitionTo(target);
        validateTargetStatusRequirements(project, target);

        int affected = projectMapper.casTransitionStatus(id, current.name(), target.name());
        if (affected == 0) {
            throw new ForbiddenException("状态流转失败，项目状态已被其他操作变更, projectId=" + id);
        }
    }

    private void normalizeInitialProjectStatus(Project project) {
        ProjectStatus requestedStatus = ProjectStatus.nullableFrom(project.getStatus());
        ProjectStatus initialStatus = requestedStatus;
        if (initialStatus == null) {
            initialStatus = project.getLeaderAccountId() == null ? ProjectStatus.OPEN : ProjectStatus.CONFIRMED;
        }
        if (!initialStatus.canBeInitialStatus()) {
            throw new IllegalArgumentException("Invalid initial project status: " + initialStatus.name());
        }
        if (initialStatus.requiresLeader() && project.getLeaderAccountId() == null) {
            throw new IllegalArgumentException("Project status " + initialStatus.name() + " requires leaderAccountId");
        }
        project.setStatus(initialStatus.name());
    }

    private void validateTargetStatusRequirements(Project project, ProjectStatus targetStatus) {
        if (targetStatus.requiresLeader() && project.getLeaderAccountId() == null) {
            throw new IllegalArgumentException("Project status " + targetStatus.name() + " requires leaderAccountId");
        }
    }

    private void validateLeaderAccount(Long leaderAccountId) {
        if (leaderAccountId == null) {
            return;
        }
        Account leader = accountMapper.selectById(leaderAccountId);
        if (leader == null) {
            throw new ResourceNotFoundException("领队账号不存在, accountId=" + leaderAccountId);
        }
        if (!ROLE_LEADER.equals(leader.getRole())) {
            throw new IllegalArgumentException("指定账号不是领队, accountId=" + leaderAccountId);
        }
    }

    private void ensureCanOperateProject(Project project, Long currentAccountId, String currentRole) {
        if (ROLE_ADMIN.equals(currentRole)) {
            return;
        }
        if (currentAccountId.equals(project.getOwnerAccountId())) {
            return;
        }
        if (currentAccountId.equals(project.getLeaderAccountId())) {
            return;
        }
        throw new ForbiddenException("仅项目拥有者或已接单领队可更新项目状态");
    }

    private ProjectPreference resolveProjectPreference(Long accountId) {
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
        return new ProjectPreference(trimToNull(regionCode), preferredTagNames);
    }

    private String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            return null;
        }
        return ProjectStatus.from(normalized).name();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ProjectPreference(String regionCode, List<String> preferredTagNames) {
    }
}
