package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.ReviewMapper;
import com.viyangle.study_tour.pojo.Review;
import com.viyangle.study_tour.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private ReviewMapper reviewMapper;

    @Override
    @Transactional
    public Long createReview(Review review) {
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);
        return review.getId();
    }

    @Override
    @Transactional
    public boolean deleteReview(Long reviewId) {
        reviewMapper.deleteReviewRelationsByReviewId(reviewId);
        return reviewMapper.deleteById(reviewId) > 0;
    }

    @Override
    @Transactional
    public boolean updateReview(Review review) {
        return reviewMapper.updateById(review) > 0;
    }

    @Override
    public Review getReviewById(Long reviewId) {
        return reviewMapper.selectById(reviewId);
    }

    @Override
    public List<Review> getAllReviews() {
        return reviewMapper.selectAll();
    }

    @Override
    public List<Review> getReviewsByProjectId(Long projectId) {
        return reviewMapper.selectByProjectId(projectId);
    }

    @Override
    public List<Review> getReviewsByRouteId(Long routeId) {
        return reviewMapper.selectByRouteId(routeId);
    }

    @Override
    public List<Review> getReviewsByToAccountId(Long toAccountId) {
        return reviewMapper.selectByToAccountId(toAccountId);
    }

    @Override
    public List<Review> getReviewsByFromAccountId(Long fromAccountId) {
        return reviewMapper.selectByFromAccountId(fromAccountId);
    }

    @Override
    public List<Review> getReviewsByReviewType(String reviewType) {
        return reviewMapper.selectByReviewType(reviewType);
    }

    @Override
    public Double getAverageScoreByToAccountId(Long toAccountId) {
        return reviewMapper.selectAverageScoreByToAccountId(toAccountId);
    }

}
