package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.LeaderOrderView;
import com.viyangle.study_tour.pojo.LeaderProfileView;
import com.viyangle.study_tour.pojo.LeaderReviewView;

import java.util.List;

public interface LeaderService {
    List<LeaderOrderView> getAvailableOrders(Long leaderAccountId, Integer pageNum, Integer pageSize);

    LeaderOrderView getOrderDetail(Long leaderAccountId, Long projectId);

    LeaderProfileView getProfile(Long leaderAccountId);

    List<LeaderReviewView> getReviews(Long leaderAccountId, Integer pageNum, Integer pageSize);
}
