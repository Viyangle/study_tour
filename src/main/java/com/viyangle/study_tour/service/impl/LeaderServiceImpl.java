package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.LeaderProfileMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ReviewMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderOrderView;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.LeaderProfileView;
import com.viyangle.study_tour.pojo.LeaderReviewView;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectStatus;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.LeaderService;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.service.RouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LeaderServiceImpl implements LeaderService {

    private static final Set<String> LEADER_ROLES = Set.of("LEADER", "BOTH");

    @Autowired
    private ProjectService projectService;

    @Autowired
    private RouteService routeService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private LeaderProfileMapper leaderProfileMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Override
    public List<LeaderOrderView> getAvailableOrders(Long leaderAccountId, Integer pageNum, Integer pageSize) {
        requireLeader(leaderAccountId);
        List<Project> projects = projectService.getAvailableProjectsForLeader(
                leaderAccountId,
                normalizePage(pageNum),
                normalizeSize(pageSize, 10)
        );

        return projects.stream()
                .map(project -> toOrderView(project, leaderAccountId))
                .filter(view -> Boolean.TRUE.equals(view.getCanAccept()))
                .toList();
    }

    @Override
    public LeaderOrderView getOrderDetail(Long leaderAccountId, Long projectId) {
        requireLeader(leaderAccountId);
        if (projectId == null) {
            throw new IllegalArgumentException("订单ID不能为空");
        }
        Project project = projectService.getProjectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("订单不存在, projectId=" + projectId);
        }
        return toOrderView(project, leaderAccountId);
    }

    @Override
    public LeaderProfileView getProfile(Long leaderAccountId) {
        Account account = requireLeader(leaderAccountId);
        LeaderProfile leaderProfile = leaderProfileMapper.selectById(leaderAccountId);
        Double averageRating = reviewMapper.selectLeaderAverageScoreByToAccountId(leaderAccountId);

        LeaderProfileView view = new LeaderProfileView();
        view.setAccountId(leaderAccountId);
        view.setUsername(account.getUsername());
        view.setAvatarUrl(account.getAvatarUrl());
        view.setRegionCode(account.getRegionCode());
        view.setIntro(leaderProfile == null ? null : leaderProfile.getIntro());
        view.setAverageRating(averageRating == null ? 0.0 : averageRating);
        view.setRatingCount(reviewMapper.countLeaderReviewsByToAccountId(leaderAccountId));
        view.setAcceptedOrderCount(projectMapper.countByLeaderAccountId(leaderAccountId));
        view.setCompletedOrderCount(projectMapper.countByLeaderAccountIdAndStatus(leaderAccountId, "DONE"));
        view.setTagNames(resolveTagNames(leaderAccountId));
        view.setRecentReviews(reviewMapper.selectRecentLeaderReviews(leaderAccountId, 3));
        return view;
    }

    @Override
    public List<LeaderReviewView> getReviews(Long leaderAccountId, Integer pageNum, Integer pageSize) {
        requireLeader(leaderAccountId);
        PageHelper.startPage(normalizePage(pageNum), normalizeSize(pageSize, 20));
        return reviewMapper.selectLeaderReviews(leaderAccountId);
    }

    private LeaderOrderView toOrderView(Project project, Long currentLeaderAccountId) {
        Account owner = project.getOwnerAccountId() == null
                ? null
                : accountMapper.selectById(project.getOwnerAccountId());
        List<RouteAttraction> routeAttractions = project.getRouteId() == null
                ? Collections.emptyList()
                : routeService.getRouteById(project.getRouteId());
        if (routeAttractions == null) {
            routeAttractions = Collections.emptyList();
        }

        String orderStatus = resolveOrderStatus(project, currentLeaderAccountId);
        LeaderOrderView view = new LeaderOrderView();
        view.setId(project.getId());
        view.setRouteId(project.getRouteId());
        view.setOwnerAccountId(project.getOwnerAccountId());
        view.setCustomerName(owner == null ? null : owner.getUsername());
        view.setCustomerAvatarUrl(owner == null ? null : owner.getAvatarUrl());
        view.setTitle(project.getTitle());
        view.setDepartureDate(project.getDepartureDate());
        view.setDepartureTime(project.getDepartureTime());
        view.setStartPointType(project.getStartPointType());
        view.setStartPoint(project.getStartPoint());
        view.setLeaderRequirements(project.getLeaderRequirements());
        view.setParticipantRequirements(project.getParticipantRequirements());
        view.setAttractionNames(routeAttractions.stream()
                .map(RouteAttraction::getName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList());
        view.setRouteAttractions(routeAttractions);
        view.setEstimatedDurationMinutes(routeAttractions.stream()
                .map(RouteAttraction::getRecommendedDuration)
                .filter(Objects::nonNull)
                .filter(duration -> duration > 0)
                .mapToInt(Integer::intValue)
                .sum());
        view.setTag(project.getTag());
        view.setPeopleCount(project.getCurrentMembers());
        view.setMaxMembers(project.getMaxMembers());
        view.setProjectStatus(project.getStatus());
        view.setOrderStatus(orderStatus);
        view.setCanAccept("AVAILABLE".equals(orderStatus));
        return view;
    }

    private String resolveOrderStatus(Project project, Long currentLeaderAccountId) {
        if (isExpired(project)) {
            return "EXPIRED";
        }
        if (project.getLeaderAccountId() != null) {
            return project.getLeaderAccountId().equals(currentLeaderAccountId)
                    ? "ACCEPTED_BY_ME"
                    : "TAKEN_BY_OTHER";
        }
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        if (status == ProjectStatus.OPEN || status == ProjectStatus.MATCHING) {
            return "AVAILABLE";
        }
        return "EXPIRED";
    }

    private boolean isExpired(Project project) {
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        if (status == ProjectStatus.DONE || status == ProjectStatus.CANCELLED) {
            return true;
        }
        LocalDate departureDate = project.getDepartureDate();
        if (departureDate == null) {
            return false;
        }
        LocalTime departureTime = project.getDepartureTime() == null
                ? LocalTime.MAX
                : project.getDepartureTime();
        return LocalDateTime.of(departureDate, departureTime).isBefore(LocalDateTime.now());
    }

    private List<String> resolveTagNames(Long accountId) {
        List<AccountTagPref> preferences = accountTagPrefMapper.selectByAccountId(accountId);
        if (preferences == null || preferences.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Tag> tagsById = tagMapper.selectAll().stream()
                .filter(tag -> tag.getId() != null)
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        return preferences.stream()
                .map(AccountTagPref::getTagId)
                .map(tagsById::get)
                .filter(Objects::nonNull)
                .map(Tag::getName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
    }

    private Account requireLeader(Long accountId) {
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ResourceNotFoundException("领队账号不存在, accountId=" + accountId);
        }
        if (account.getRole() == null || !LEADER_ROLES.contains(account.getRole().toUpperCase(Locale.ROOT))) {
            throw new ForbiddenException("当前账号不是领队");
        }
        return account;
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizeSize(Integer pageSize, int defaultSize) {
        if (pageSize == null || pageSize < 1) {
            return defaultSize;
        }
        return Math.min(pageSize, 100);
    }
}
