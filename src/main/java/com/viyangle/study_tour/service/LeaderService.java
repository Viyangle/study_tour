package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.LeaderProfileView;
import com.viyangle.study_tour.pojo.LeaderReviewView;

import java.util.List;

public interface LeaderService {
    LeaderProfileView getProfile(Long leaderAccountId);

    List<LeaderReviewView> getReviews(Long leaderAccountId, Integer pageNum, Integer pageSize);
}
