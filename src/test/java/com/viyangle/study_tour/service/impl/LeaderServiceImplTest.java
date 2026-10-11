package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountTagPrefMapper accountTagPrefMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private LeaderProfileMapper leaderProfileMapper;

    @Mock
    private ReviewMapper reviewMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private LeaderServiceImpl leaderService;

    @Test
    void profileContainsLiveStatsTagsAndRecentReviews() {
        when(accountMapper.selectById(20L)).thenReturn(account(20L, "BOTH", "领队"));
        when(leaderProfileMapper.selectById(20L)).thenReturn(new LeaderProfile(20L, "研学领队", 99, 99));
        when(reviewMapper.selectLeaderAverageScoreByToAccountId(20L)).thenReturn(4.5);
        when(reviewMapper.countLeaderReviewsByToAccountId(20L)).thenReturn(8);
        when(projectMapper.countByLeaderAccountId(20L)).thenReturn(12);
        when(projectMapper.countByLeaderAccountIdAndStatus(20L, "DONE")).thenReturn(7);
        when(accountTagPrefMapper.selectByAccountId(20L)).thenReturn(List.of(new AccountTagPref(20L, 2L)));
        when(tagMapper.selectAll()).thenReturn(List.of(new Tag(2L, "博物馆研学", "INTEREST", null)));
        LeaderReviewView review = new LeaderReviewView(
                1L, 5L, 7L, 10L, "客户甲", null, 5, "很专业", LocalDateTime.now()
        );
        when(reviewMapper.selectRecentLeaderReviews(20L, 3)).thenReturn(List.of(review));

        LeaderProfileView profile = leaderService.getProfile(20L);

        assertEquals(4.5, profile.getAverageRating());
        assertEquals(8, profile.getRatingCount());
        assertEquals(12, profile.getAcceptedProjectCount());
        assertEquals(7, profile.getCompletedProjectCount());
        assertEquals(List.of("博物馆研学"), profile.getTagNames());
        assertEquals("客户甲", profile.getRecentReviews().get(0).getReviewerName());
    }

    @Test
    void userAccountCannotUseLeaderFacade() {
        when(accountMapper.selectById(20L)).thenReturn(account(20L, "USER", "普通用户"));

        assertThrows(ForbiddenException.class, () -> leaderService.getProfile(20L));
    }

    private Account account(Long id, String role, String username) {
        Account account = new Account();
        account.setId(id);
        account.setRole(role);
        account.setUsername(username);
        return account;
    }
}
