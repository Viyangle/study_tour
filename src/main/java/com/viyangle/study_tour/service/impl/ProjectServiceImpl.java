package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ProjectMemberMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.PageResponse;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.pojo.ProjectStatus;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.StartPointType;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.ChatService;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.service.RouteService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    private RouteMapper routeMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private RouteService routeService;

    @Autowired
    private KnowledgeGraphRecommendService kgRecommendService;

    @Transactional
    @Override
    public Long createProject(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("项目发布信息不能为空");
        }
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        if (currentAccountId != null) {
            project.setOwnerAccountId(currentAccountId);
        }
        if (project.getOwnerAccountId() == null) {
            throw new UnauthorizedException("未认证用户");
        }

        Route route = requireRoute(project.getRouteId());
        project.setRegionAdcode(route.getRegionAdcode());
        project.setTag(route.getTag());
        int representedCount = normalizeRepresentedCount(project.getRepresentedCount());
        normalizePublishDetails(project, representedCount);
        normalizeInitialProjectStatus(project);
        validateLeaderAccount(project.getLeaderAccountId());
        projectMapper.insert(project);
        projectMemberMapper.insert(new ProjectMember(
                null,
                project.getId(),
                project.getOwnerAccountId(),
                "JOINED",
                representedCount,
                LocalDateTime.now()
        ));
        projectMapper.refreshCurrentMembersById(project.getId());
        syncProjectGroupChat(project, ProjectStatus.from(project.getStatus()));
        return project.getId();
    }

    @Override
    public List<Project> getAllProjects() {
        return projectMapper.selectAll();
    }

    @Override
    public List<Project> getPagedProjectsByPreference(Long accountId, Integer pageNum, Integer pageSize) {
        return getPagedProjectsByPreference(accountId, pageNum, pageSize, null);
    }

    @Override
    public List<Project> getPagedProjectsByPreference(Long accountId,
                                                      Integer pageNum,
                                                      Integer pageSize,
                                                      String keyword) {
        if (trimToNull(keyword) != null) {
            return filterProjects(
                    accountId,
                    pageNum,
                    pageSize,
                    keyword,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

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

    private List<Project> getAvailableProjectsForLeader(Long accountId, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        ProjectPreference preference = resolveProjectPreference(accountId);
        PageHelper.startPage(page, size);
        return projectMapper.selectAvailableForLeader(
                preference.preferredTagNames(),
                preference.regionCode(),
                LocalDate.now(),
                LocalTime.now()
        );
    }

    @Override
    public PageResponse<Project> getAvailableProjectPage(Long accountId,
                                                         Integer pageNum,
                                                         Integer pageSize) {
        Account viewer = requireAccount(accountId);
        if (!isLeader(viewer)) {
            throw new ForbiddenException("当前账号不是领队");
        }
        int page = normalizePage(pageNum);
        int size = normalizePageSize(pageSize);
        List<Project> projects = getAvailableProjectsForLeader(accountId, page, size);
        PageInfo<Project> pageInfo = new PageInfo<>(projects);
        projects.forEach(project -> enrichProject(project, viewer));
        return PageResponse.of(projects, pageInfo.getTotal(), page, size);
    }

    @Override
    public PageResponse<Project> getMyProjects(Long accountId,
                                               String relation,
                                               String status,
                                               Integer pageNum,
                                               Integer pageSize) {
        Account viewer = requireAccount(accountId);
        int page = normalizePage(pageNum);
        int size = normalizePageSize(pageSize);

        PageHelper.startPage(page, size);
        List<Project> projects = projectMapper.selectByAccountRelation(
                accountId,
                normalizeRelation(relation),
                normalizeStatus(status)
        );
        PageInfo<Project> pageInfo = new PageInfo<>(projects);
        projects.forEach(project -> enrichProject(project, viewer));
        return PageResponse.of(projects, pageInfo.getTotal(), page, size);
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
    public void joinProject(Long id, Long accountId, Integer representedCountValue) {
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        int representedCount = normalizeRepresentedCount(representedCountValue);

        Project project = projectMapper.selectByIdForUpdate(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }
        if (hasDeparted(project)) {
            throw new ForbiddenException("项目已过出发时间, projectId=" + id);
        }
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        if (status != ProjectStatus.OPEN && status != ProjectStatus.MATCHING) {
            throw new ForbiddenException("当前项目状态不可加入, projectId=" + id + ", status=" + status.name());
        }

        ProjectMember existing = projectMemberMapper.selectByProjectIdAndAccountId(id, accountId);
        if (existing != null) {
            throw new ForbiddenException("已加入该项目, projectId=" + id + ", accountId=" + accountId);
        }

        int affected = projectMapper.casIncrementCurrentMembers(id, representedCount);
        if (affected == 0) {
            throw new ForbiddenException(
                    "加入后将超过项目人数上限, representedCount=" + representedCount
                            + ", max=" + project.getMaxMembers()
            );
        }

        projectMemberMapper.insert(new ProjectMember(
                null,
                id,
                accountId,
                "JOINED",
                representedCount,
                LocalDateTime.now()
        ));
        chatService.joinProjectGroup(id, accountId);
    }

    @Override
    public Project getProjectDetail(Long accountId, Long id) {
        Account viewer = requireAccount(accountId);
        if (id == null) {
            throw new IllegalArgumentException("项目ID不能为空");
        }
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }
        enrichProject(project, viewer);
        return project;
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

        Project project = projectMapper.selectByIdForUpdate(id);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + id);
        }
        if (hasDeparted(project)) {
            throw new ForbiddenException("项目已过出发时间, projectId=" + id);
        }

        if (ProjectStatus.CONFIRMED.name().equals(project.getStatus())
                && leaderAccountId.equals(project.getLeaderAccountId())) {
            chatService.createProjectGroup(id, project.getOwnerAccountId(), leaderAccountId);
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
        chatService.createProjectGroup(id, project.getOwnerAccountId(), leaderAccountId);
    }

    private boolean hasDeparted(Project project) {
        if (project.getDepartureDate() == null) {
            return false;
        }
        LocalTime departureTime = project.getDepartureTime() == null
                ? LocalTime.MAX
                : project.getDepartureTime();
        return LocalDateTime.of(project.getDepartureDate(), departureTime)
                .isBefore(LocalDateTime.now());
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
            chatService.createProjectGroup(
                    existingProject.getId(),
                    existingProject.getOwnerAccountId(),
                    existingProject.getLeaderAccountId()
            );
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
        chatService.createProjectGroup(
                existingProject.getId(),
                existingProject.getOwnerAccountId(),
                project.getLeaderAccountId()
        );
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
            syncProjectGroupChat(project, target);
            return;
        }

        current.assertCanTransitionTo(target);
        validateTargetStatusRequirements(project, target);

        int affected = projectMapper.casTransitionStatus(id, current.name(), target.name());
        if (affected == 0) {
            throw new ForbiddenException("状态流转失败，项目状态已被其他操作变更, projectId=" + id);
        }
        syncProjectGroupChat(project, target);
    }

    private void syncProjectGroupChat(Project project, ProjectStatus status) {
        if (status == ProjectStatus.OPEN
                || status == ProjectStatus.MATCHING
                || status == ProjectStatus.CONFIRMED
                || status == ProjectStatus.IN_PROGRESS) {
            chatService.createProjectGroup(
                    project.getId(),
                    project.getOwnerAccountId(),
                    project.getLeaderAccountId()
            );
        } else if (status == ProjectStatus.DONE || status == ProjectStatus.CANCELLED) {
            chatService.deleteProjectGroup(project.getId());
        }
    }

    private Route requireRoute(Long routeId) {
        if (routeId == null) {
            throw new IllegalArgumentException("路线ID不能为空");
        }
        Route route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ResourceNotFoundException("路线不存在, routeId=" + routeId);
        }
        return route;
    }

    private int normalizeRepresentedCount(Integer representedCount) {
        if (representedCount == null || representedCount <= 0) {
            throw new IllegalArgumentException("代表参团人数必须是正整数");
        }
        return representedCount;
    }

    private void normalizePublishDetails(Project project, int representedCount) {
        if (project.getDepartureDate() == null) {
            throw new IllegalArgumentException("出发日期不能为空");
        }
        if (project.getDepartureDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("出发日期不能早于今天");
        }
        if (project.getDepartureTime() == null) {
            throw new IllegalArgumentException("出发时间不能为空");
        }
        StartPointType startPointType = StartPointType.from(project.getStartPointType());
        project.setStartPointType(startPointType.name());
        project.setStartPoint(trimToNull(project.getStartPoint()));
        if (project.getStartPoint() == null) {
            throw new IllegalArgumentException("起点不能为空；当前位置需提交前端解析出的地址或坐标");
        }
        if (project.getStartPoint().length() > 255) {
            throw new IllegalArgumentException("起点长度不能超过255个字符");
        }

        if (project.getMaxMembers() != null) {
            if (project.getMaxMembers() <= 0) {
                throw new IllegalArgumentException("项目人数上限必须是正整数");
            }
            if (representedCount > project.getMaxMembers()) {
                throw new IllegalArgumentException("代表参团人数不能超过项目人数上限");
            }
        }
        project.setCurrentMembers(representedCount);
        project.setLeaderRequirements(trimToNull(project.getLeaderRequirements()));
        project.setParticipantRequirements(trimToNull(project.getParticipantRequirements()));
        project.setTitle(trimToNull(project.getTitle()));
        if (project.getTitle() == null) {
            String tag = trimToNull(project.getTag());
            project.setTitle(tag == null ? "研学路线拼单" : tag + "研学拼单");
        }
        if (project.getTitle().length() > 100) {
            throw new IllegalArgumentException("项目标题长度不能超过100个字符");
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

    private void enrichProject(Project project, Account viewer) {
        Account publisher = selectAccount(project.getOwnerAccountId());
        Account leader = selectAccount(project.getLeaderAccountId());
        List<RouteAttraction> routeAttractions = project.getRouteId() == null
                ? Collections.emptyList()
                : routeService.getRouteById(project.getRouteId());
        if (routeAttractions == null) {
            routeAttractions = Collections.emptyList();
        }

        ProjectMember membership = projectMemberMapper.selectByProjectIdAndAccountId(
                project.getId(),
                viewer.getId()
        );
        boolean activeMember = membership != null
                && ("JOINED".equalsIgnoreCase(membership.getJoinStatus())
                || "COMPLETED".equalsIgnoreCase(membership.getJoinStatus()));
        ChatSession group = chatSessionMapper.selectByProjectId(project.getId());

        project.setPublisherName(publisher == null ? null : publisher.getUsername());
        project.setPublisherAvatarUrl(publisher == null ? null : publisher.getAvatarUrl());
        project.setLeaderName(leader == null ? null : leader.getUsername());
        project.setLeaderAvatarUrl(leader == null ? null : leader.getAvatarUrl());
        project.setAttractionNames(routeAttractions.stream()
                .map(RouteAttraction::getName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList());
        project.setRouteAttractions(routeAttractions);
        project.setEstimatedDurationMinutes(routeAttractions.stream()
                .map(RouteAttraction::getRecommendedDuration)
                .filter(Objects::nonNull)
                .filter(duration -> duration > 0)
                .mapToInt(Integer::intValue)
                .sum());
        project.setAvailabilityStatus(resolveAvailabilityStatus(project, viewer.getId()));
        project.setViewerRole(resolveViewerRole(project, viewer.getId(), activeMember));
        project.setCanAccept(isLeader(viewer)
                && "AVAILABLE".equals(project.getAvailabilityStatus()));
        project.setCanJoin(canJoin(project, viewer, membership));
        project.setCanManageGroup(
                ROLE_ADMIN.equalsIgnoreCase(viewer.getRole())
                        || Objects.equals(viewer.getId(), project.getOwnerAccountId())
        );
        project.setGroupId(group != null && "ACTIVE".equalsIgnoreCase(group.getStatus())
                ? group.getId()
                : null);
    }

    private boolean canJoin(Project project, Account viewer, ProjectMember membership) {
        boolean canParticipate = viewer.getRole() != null
                && ("USER".equalsIgnoreCase(viewer.getRole())
                || "BOTH".equalsIgnoreCase(viewer.getRole()));
        if (!canParticipate
                || membership != null
                || Objects.equals(viewer.getId(), project.getOwnerAccountId())) {
            return false;
        }
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        if (status != ProjectStatus.OPEN && status != ProjectStatus.MATCHING) {
            return false;
        }
        if (isProjectExpired(project)) {
            return false;
        }
        return project.getMaxMembers() == null
                || project.getCurrentMembers() == null
                || project.getCurrentMembers() < project.getMaxMembers();
    }

    private String resolveViewerRole(Project project, Long accountId, boolean activeMember) {
        if (Objects.equals(accountId, project.getOwnerAccountId())) {
            return "PUBLISHER";
        }
        if (Objects.equals(accountId, project.getLeaderAccountId())) {
            return "LEADER";
        }
        return activeMember ? "PARTICIPANT" : "NONE";
    }

    private String resolveAvailabilityStatus(Project project, Long accountId) {
        if (isProjectExpired(project)) {
            return "EXPIRED";
        }
        if (project.getLeaderAccountId() != null) {
            return project.getLeaderAccountId().equals(accountId)
                    ? "ACCEPTED_BY_ME"
                    : "TAKEN_BY_OTHER";
        }
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        return status == ProjectStatus.OPEN || status == ProjectStatus.MATCHING
                ? "AVAILABLE"
                : "EXPIRED";
    }

    private boolean isProjectExpired(Project project) {
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        return status == ProjectStatus.DONE
                || status == ProjectStatus.CANCELLED
                || hasDeparted(project);
    }

    private Account requireAccount(Long accountId) {
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ResourceNotFoundException("账号不存在, accountId=" + accountId);
        }
        return account;
    }

    private Account selectAccount(Long accountId) {
        return accountId == null ? null : accountMapper.selectById(accountId);
    }

    private boolean isLeader(Account account) {
        if (account.getRole() == null) {
            return false;
        }
        String role = account.getRole().toUpperCase(Locale.ROOT);
        return ROLE_LEADER.equals(role) || "BOTH".equals(role);
    }

    private String normalizeRelation(String relation) {
        String normalized = trimToNull(relation);
        normalized = normalized == null ? "ALL" : normalized.toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "PUBLISHED", "LEADING", "JOINED").contains(normalized)) {
            throw new IllegalArgumentException("无效的项目关系: " + relation);
        }
        return normalized;
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
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
