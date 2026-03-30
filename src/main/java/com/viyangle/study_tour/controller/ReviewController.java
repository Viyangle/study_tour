package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Review;
import com.viyangle.study_tour.pojo.ReviewTagScore;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    @OperationLog(value = "创建评价", type = "REVIEW_CREATE")
    @RequireRole({"USER", "LEADER"})
    public Result createReview(@RequestBody Map<String, Object> request) {
        log.info("创建评价");
        
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
            
            List<ReviewTagScore> tagScores = null;
            if (request.containsKey("tagScores")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tagScoreMaps = (List<Map<String, Object>>) request.get("tagScores");
                tagScores = tagScoreMaps.stream()
                    .map(map -> new ReviewTagScore(
                        null,
                        Long.valueOf(map.get("tagId").toString()),
                        Integer.valueOf(map.get("score").toString())
                    ))
                    .toList();
            }
            
            Long reviewId = reviewService.createReview(review, tagScores);
            return Result.success(reviewId);
            
        } catch (Exception e) {
            log.error("创建评价失败", e);
            return Result.error("创建评价失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(value = "删除评价", type = "REVIEW_DELETE")
    @RequireRole({"USER", "LEADER"})
    public Result deleteReview(@PathVariable Long id) {
        log.info("删除评价：{}", id);
        boolean success = reviewService.deleteReview(id);
        return success ? Result.success() : Result.error("删除评价失败");
    }

    @PutMapping("/{id}")
    @OperationLog(value = "更新评价", type = "REVIEW_UPDATE")
    @RequireRole({"USER", "LEADER"})
    public Result updateReview(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        log.info("更新评价：{}", id);
        
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
            
            List<ReviewTagScore> tagScores = null;
            if (request.containsKey("tagScores")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tagScoreMaps = (List<Map<String, Object>>) request.get("tagScores");
                tagScores = tagScoreMaps.stream()
                    .map(map -> new ReviewTagScore(
                        id,
                        Long.valueOf(map.get("tagId").toString()),
                        Integer.valueOf(map.get("score").toString())
                    ))
                    .toList();
            }
            
            boolean success = reviewService.updateReview(review, tagScores);
            return success ? Result.success() : Result.error("更新评价失败");
            
        } catch (Exception e) {
            log.error("更新评价失败", e);
            return Result.error("更新评价失败：" + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @OperationLog(value = "获取评价详情", type = "REVIEW_QUERY")
    public Result getReviewById(@PathVariable Long id) {
        log.info("获取评价详情：{}", id);
        Review review = reviewService.getReviewById(id);
        
        if (review == null) {
            return Result.error("评价不存在");
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("review", review);
        result.put("tagScores", reviewService.getTagScoresByReviewId(id));
        
        return Result.success(result);
    }

    @GetMapping
    @OperationLog(value = "获取所有评价", type = "REVIEW_QUERY")
    public Result getAllReviews() {
        log.info("获取所有评价");
        return Result.success(reviewService.getAllReviews());
    }

    @GetMapping("/project/{projectId}")
    @OperationLog(value = "按项目查询评价", type = "REVIEW_QUERY")
    public Result getReviewsByProjectId(@PathVariable Long projectId) {
        log.info("获取项目评价列表：{}", projectId);
        return Result.success(reviewService.getReviewsByProjectId(projectId));
    }

    @GetMapping("/route/{routeId}")
    @OperationLog(value = "按路线查询评价", type = "REVIEW_QUERY")
    public Result getReviewsByRouteId(@PathVariable Long routeId) {
        log.info("获取路线评价列表：{}", routeId);
        return Result.success(reviewService.getReviewsByRouteId(routeId));
    }

    @GetMapping("/to-account/{accountId}")
    @OperationLog(value = "查询用户收到的评价", type = "REVIEW_QUERY")
    public Result getReviewsByToAccountId(@PathVariable Long accountId) {
        log.info("获取用户收到的评价列表：{}", accountId);
        return Result.success(reviewService.getReviewsByToAccountId(accountId));
    }

    @GetMapping("/from-account/{accountId}")
    @OperationLog(value = "查询用户发出的评价", type = "REVIEW_QUERY")
    public Result getReviewsByFromAccountId(@PathVariable Long accountId) {
        log.info("获取用户发出的评价列表：{}", accountId);
        return Result.success(reviewService.getReviewsByFromAccountId(accountId));
    }

    @GetMapping("/type/{reviewType}")
    @OperationLog(value = "按类型查询评价", type = "REVIEW_QUERY")
    public Result getReviewsByReviewType(@PathVariable String reviewType) {
        log.info("获取类型评价列表：{}", reviewType);
        return Result.success(reviewService.getReviewsByReviewType(reviewType));
    }

    @GetMapping("/average-score/{accountId}")
    @OperationLog(value = "获取用户平均评分", type = "REVIEW_QUERY")
    public Result getAverageScoreByToAccountId(@PathVariable Long accountId) {
        log.info("获取用户平均评分：{}", accountId);
        Double averageScore = reviewService.getAverageScoreByToAccountId(accountId);
        return Result.success(averageScore != null ? averageScore : 0.0);
    }
}