package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Review;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    @OperationLog(value = "Create review", type = "REVIEW_CREATE")
    @RequireRole({"USER", "LEADER"})
    public Result createReview(@RequestBody Map<String, Object> request) {
        log.info("Create review");
        try {
            Review review = new Review();
            review.setProjectId(Long.valueOf(request.get("projectId").toString()));
            review.setRouteId(Long.valueOf(request.get("routeId").toString()));
            review.setFromAccountId(Long.valueOf(request.get("fromAccountId").toString()));

            if (request.containsKey("toAccountId") && request.get("toAccountId") != null) {
                review.setToAccountId(Long.valueOf(request.get("toAccountId").toString()));
            }

            review.setReviewType(request.get("reviewType").toString());
            review.setOverallScore(Integer.valueOf(request.get("overallScore").toString()));

            if (request.containsKey("content")) {
                review.setContent(request.get("content").toString());
            }

            Long reviewId = reviewService.createReview(review);
            return Result.success(reviewId);
        } catch (Exception e) {
            log.error("Create review failed", e);
            return Result.error("Create review failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(value = "Delete review", type = "REVIEW_DELETE")
    @RequireRole({"USER", "LEADER"})
    public Result deleteReview(@PathVariable Long id) {
        log.info("Delete review: {}", id);
        boolean success = reviewService.deleteReview(id);
        return success ? Result.success() : Result.error("Delete review failed");
    }

    @PutMapping("/{id}")
    @OperationLog(value = "Update review", type = "REVIEW_UPDATE")
    @RequireRole({"USER", "LEADER"})
    public Result updateReview(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        log.info("Update review: {}", id);
        try {
            Review review = new Review();
            review.setId(id);
            review.setProjectId(Long.valueOf(request.get("projectId").toString()));
            review.setRouteId(Long.valueOf(request.get("routeId").toString()));
            review.setFromAccountId(Long.valueOf(request.get("fromAccountId").toString()));

            if (request.containsKey("toAccountId") && request.get("toAccountId") != null) {
                review.setToAccountId(Long.valueOf(request.get("toAccountId").toString()));
            }

            review.setReviewType(request.get("reviewType").toString());
            review.setOverallScore(Integer.valueOf(request.get("overallScore").toString()));

            if (request.containsKey("content")) {
                review.setContent(request.get("content").toString());
            }

            boolean success = reviewService.updateReview(review);
            return success ? Result.success() : Result.error("Update review failed");
        } catch (Exception e) {
            log.error("Update review failed", e);
            return Result.error("Update review failed: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @OperationLog(value = "Get review detail", type = "REVIEW_QUERY")
    public Result getReviewById(@PathVariable Long id) {
        log.info("Get review detail: {}", id);
        Review review = reviewService.getReviewById(id);
        if (review == null) {
            return Result.error("Review not found");
        }
        return Result.success(review);
    }

    @GetMapping
    @OperationLog(value = "Get all reviews", type = "REVIEW_QUERY")
    public Result getAllReviews() {
        log.info("Get all reviews");
        return Result.success(reviewService.getAllReviews());
    }

    @GetMapping("/project/{projectId}")
    @OperationLog(value = "Get reviews by project", type = "REVIEW_QUERY")
    public Result getReviewsByProjectId(@PathVariable Long projectId) {
        log.info("Get reviews by project: {}", projectId);
        return Result.success(reviewService.getReviewsByProjectId(projectId));
    }

    @GetMapping("/route/{routeId}")
    @OperationLog(value = "Get reviews by route", type = "REVIEW_QUERY")
    public Result getReviewsByRouteId(@PathVariable Long routeId) {
        log.info("Get reviews by route: {}", routeId);
        return Result.success(reviewService.getReviewsByRouteId(routeId));
    }

    @GetMapping("/leader/{accountId}")
    @OperationLog(value = "Get reviews to account", type = "REVIEW_QUERY")
    public Result getReviewsByToAccountId(@PathVariable Long accountId) {
        log.info("Get reviews to account: {}", accountId);
        return Result.success(reviewService.getReviewsByToAccountId(accountId));
    }

    @GetMapping("/user/{accountId}")
    @OperationLog(value = "Get reviews from account", type = "REVIEW_QUERY")
    public Result getReviewsByFromAccountId(@PathVariable Long accountId) {
        log.info("Get reviews from account: {}", accountId);
        return Result.success(reviewService.getReviewsByFromAccountId(accountId));
    }

    @GetMapping("/type/{reviewType}")
    @OperationLog(value = "Get reviews by type", type = "REVIEW_QUERY")
    public Result getReviewsByReviewType(@PathVariable String reviewType) {
        log.info("Get reviews by type: {}", reviewType);
        return Result.success(reviewService.getReviewsByReviewType(reviewType));
    }

    @GetMapping("/average-score/{accountId}")
    @OperationLog(value = "Get average score", type = "REVIEW_QUERY")
    public Result getAverageScoreByToAccountId(@PathVariable Long accountId) {
        log.info("Get average score: {}", accountId);
        Double averageScore = reviewService.getAverageScoreByToAccountId(accountId);
        return Result.success(averageScore != null ? averageScore : 0.0);
    }
}
