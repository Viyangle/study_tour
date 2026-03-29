package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.ReviewMapper;
import com.viyangle.study_tour.mapper.ReviewTagScoreMapper;
import com.viyangle.study_tour.pojo.Review;
import com.viyangle.study_tour.pojo.ReviewTagScore;
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

    @Autowired
    private ReviewTagScoreMapper reviewTagScoreMapper;

    @Override
    @Transactional
    public Long createReview(Review review, List<ReviewTagScore> tagScores) {
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);

        if (tagScores != null && !tagScores.isEmpty()) {
            for (ReviewTagScore tagScore : tagScores) {
                tagScore.setReviewId(review.getId());
                reviewTagScoreMapper.insert(tagScore);
            }
        }

        return review.getId();
    }

    @Override
    @Transactional
    public boolean deleteReview(Long reviewId) {
        reviewTagScoreMapper.deleteByReviewId(reviewId);
        return reviewMapper.deleteById(reviewId) > 0;
    }

    @Override
    @Transactional
    public boolean updateReview(Review review, List<ReviewTagScore> tagScores) {
        int rows = reviewMapper.updateById(review);

        if (rows > 0 && tagScores != null) {
            reviewTagScoreMapper.deleteByReviewId(review.getId());
            for (ReviewTagScore tagScore : tagScores) {
                tagScore.setReviewId(review.getId());
                reviewTagScoreMapper.insert(tagScore);
            }
        }

        return rows > 0;
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

    @Override
    public List<ReviewTagScore> getTagScoresByReviewId(Long reviewId) {
        return reviewTagScoreMapper.selectByReviewId(reviewId);
    }
}