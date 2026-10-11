package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.ReviewMapper;
import com.viyangle.study_tour.pojo.Review;
import com.viyangle.study_tour.service.ReviewService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ReviewServiceImpl implements ReviewService {

    private static final String ROLE_ADMIN = "ADMIN";

    @Autowired
    private ReviewMapper reviewMapper;

    @Override
    @Transactional
    public Long createReview(Review review) {
        if (review == null) {
            throw new IllegalArgumentException("review cannot be null");
        }

        // The authenticated account is the only trusted source of authorship.
        // This also keeps direct service callers from forging fromAccountId.
        review.setFromAccountId(requireCurrentAccountId());
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);
        return review.getId();
    }

    @Override
    @Transactional
    public boolean deleteReview(Long reviewId) {
        Long currentAccountId = requireCurrentAccountId();
        Review existing = requireExistingReview(reviewId);
        requireOwnerOrAdmin(existing, currentAccountId);

        reviewMapper.deleteReviewRelationsByReviewId(reviewId);
        return reviewMapper.deleteById(reviewId) > 0;
    }

    @Override
    @Transactional
    public boolean updateReview(Review review) {
        if (review == null) {
            throw new IllegalArgumentException("review cannot be null");
        }

        Long currentAccountId = requireCurrentAccountId();
        Review existing = requireExistingReview(review.getId());
        requireOwnerOrAdmin(existing, currentAccountId);

        // Keep review ownership and context immutable.  In particular, a
        // caller cannot change the author, project, route, target account, or
        // review type after the ownership check has succeeded.
        Review update = new Review();
        update.setId(existing.getId());
        update.setProjectId(existing.getProjectId());
        update.setRouteId(existing.getRouteId());
        update.setFromAccountId(existing.getFromAccountId());
        update.setToAccountId(existing.getToAccountId());
        update.setReviewType(existing.getReviewType());
        update.setOverallScore(review.getOverallScore());
        update.setContent(review.getContent());
        update.setCreatedAt(existing.getCreatedAt());
        return reviewMapper.updateById(update) > 0;
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

    private Long requireCurrentAccountId() {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        if (currentAccountId == null) {
            throw new UnauthorizedException("未登录用户");
        }
        return currentAccountId;
    }

    private Review requireExistingReview(Long reviewId) {
        if (reviewId == null) {
            throw new IllegalArgumentException("reviewId cannot be null");
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new ResourceNotFoundException("Review not found, reviewId=" + reviewId);
        }
        return review;
    }

    private void requireOwnerOrAdmin(Review review, Long currentAccountId) {
        if (isAdmin() || Objects.equals(review.getFromAccountId(), currentAccountId)) {
            return;
        }
        throw new ForbiddenException("Only the review author or an administrator can modify this review");
    }

    private boolean isAdmin() {
        String currentRole = SecurityContextUtil.currentRole();
        return currentRole != null && ROLE_ADMIN.equalsIgnoreCase(currentRole.trim());
    }

}
