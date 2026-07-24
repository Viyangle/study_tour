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
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.LeaderProfileView;
import com.viyangle.study_tour.pojo.LeaderReviewView;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.LeaderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        view.setAcceptedProjectCount(projectMapper.countByLeaderAccountId(leaderAccountId));
        view.setCompletedProjectCount(projectMapper.countByLeaderAccountIdAndStatus(leaderAccountId, "DONE"));
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
