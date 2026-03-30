package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Review;

import java.util.List;

public interface ReviewService {

    Long createReview(Review review);

    boolean deleteReview(Long reviewId);

    boolean updateReview(Review review);

    Review getReviewById(Long reviewId);

    List<Review> getAllReviews();

    List<Review> getReviewsByProjectId(Long projectId);

    List<Review> getReviewsByRouteId(Long routeId);

    List<Review> getReviewsByToAccountId(Long toAccountId);

    List<Review> getReviewsByFromAccountId(Long fromAccountId);

    List<Review> getReviewsByReviewType(String reviewType);

    Double getAverageScoreByToAccountId(Long toAccountId);
}
