package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.ReviewMapper;
import com.viyangle.study_tour.pojo.Review;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplSecurityTest {

    @Mock
    private ReviewMapper reviewMapper;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReviewUsesAuthenticatedAccountInsteadOfForgedAuthor() {
        authenticate(101L, "USER");
        Review request = review(0L, 10L, 20L, 999L, 201L, "USER_TO_LEADER", 5, "很好");
        when(reviewMapper.insert(any(Review.class))).thenAnswer(invocation -> {
            invocation.<Review>getArgument(0).setId(7L);
            return 1;
        });

        Long reviewId = reviewService.createReview(request);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).insert(captor.capture());
        assertEquals(7L, reviewId);
        assertEquals(101L, captor.getValue().getFromAccountId());
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void anonymousUserCannotCreateReview() {
        Review request = review(0L, 10L, 20L, 999L, 201L, "USER_TO_LEADER", 5, "很好");

        assertThrows(UnauthorizedException.class, () -> reviewService.createReview(request));

        verifyNoInteractions(reviewMapper);
    }

    @Test
    void authorCanUpdateMutableFieldsButCannotChangeReviewContext() {
        authenticate(101L, "USER");
        Review existing = review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "旧内容");
        when(reviewMapper.selectById(7L)).thenReturn(existing);
        when(reviewMapper.updateById(any(Review.class))).thenReturn(1);

        Review request = review(7L, 999L, 998L, 202L, 303L, "LEADER_TO_USER", 3, "新内容");
        boolean updated = reviewService.updateReview(request);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).updateById(captor.capture());
        Review persistedUpdate = captor.getValue();
        assertEquals(true, updated);
        assertEquals(7L, persistedUpdate.getId());
        assertEquals(10L, persistedUpdate.getProjectId());
        assertEquals(20L, persistedUpdate.getRouteId());
        assertEquals(101L, persistedUpdate.getFromAccountId());
        assertEquals(201L, persistedUpdate.getToAccountId());
        assertEquals("USER_TO_LEADER", persistedUpdate.getReviewType());
        assertEquals(3, persistedUpdate.getOverallScore());
        assertEquals("新内容", persistedUpdate.getContent());
    }

    @Test
    void anotherUserCannotUpdateReview() {
        authenticate(202L, "USER");
        when(reviewMapper.selectById(7L)).thenReturn(
                review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "旧内容"));

        assertThrows(ForbiddenException.class,
                () -> reviewService.updateReview(review(7L, 10L, 20L, 202L, 201L,
                        "USER_TO_LEADER", 1, "伪造更新")));

        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    @Test
    void administratorCanUpdateAnotherUsersReview() {
        authenticate(900L, "ADMIN");
        when(reviewMapper.selectById(7L)).thenReturn(
                review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "旧内容"));
        when(reviewMapper.updateById(any(Review.class))).thenReturn(1);

        boolean updated = reviewService.updateReview(
                review(7L, 999L, 998L, 202L, 303L, "LEADER_TO_USER", 4, "管理员修订"));

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).updateById(captor.capture());
        assertEquals(true, updated);
        assertEquals(101L, captor.getValue().getFromAccountId());
        assertEquals(10L, captor.getValue().getProjectId());
        assertEquals(20L, captor.getValue().getRouteId());
        assertEquals(201L, captor.getValue().getToAccountId());
        assertEquals("USER_TO_LEADER", captor.getValue().getReviewType());
    }

    @Test
    void anonymousUserCannotUpdateReview() {
        assertThrows(UnauthorizedException.class,
                () -> reviewService.updateReview(review(7L, 10L, 20L, 101L, 201L,
                        "USER_TO_LEADER", 4, "更新")));

        verifyNoInteractions(reviewMapper);
    }

    @Test
    void authorCanDeleteReview() {
        authenticate(101L, "USER");
        when(reviewMapper.selectById(7L)).thenReturn(
                review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "内容"));
        when(reviewMapper.deleteById(7L)).thenReturn(1);

        boolean deleted = reviewService.deleteReview(7L);

        assertEquals(true, deleted);
        verify(reviewMapper).deleteReviewRelationsByReviewId(7L);
        verify(reviewMapper).deleteById(7L);
    }

    @Test
    void anotherUserCannotDeleteReview() {
        authenticate(202L, "USER");
        when(reviewMapper.selectById(7L)).thenReturn(
                review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "内容"));

        assertThrows(ForbiddenException.class, () -> reviewService.deleteReview(7L));

        verify(reviewMapper, never()).deleteReviewRelationsByReviewId(7L);
        verify(reviewMapper, never()).deleteById(7L);
    }

    @Test
    void administratorCanDeleteAnotherUsersReview() {
        authenticate(900L, "ADMIN");
        when(reviewMapper.selectById(7L)).thenReturn(
                review(7L, 10L, 20L, 101L, 201L, "USER_TO_LEADER", 5, "内容"));
        when(reviewMapper.deleteById(7L)).thenReturn(1);

        boolean deleted = reviewService.deleteReview(7L);

        assertEquals(true, deleted);
        verify(reviewMapper).deleteReviewRelationsByReviewId(7L);
        verify(reviewMapper).deleteById(7L);
    }

    @Test
    void anonymousUserCannotDeleteReview() {
        assertThrows(UnauthorizedException.class, () -> reviewService.deleteReview(7L));

        verifyNoInteractions(reviewMapper);
    }

    @Test
    void updatingMissingReviewReturnsNotFound() {
        authenticate(101L, "USER");
        when(reviewMapper.selectById(404L)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
                () -> reviewService.updateReview(review(404L, 10L, 20L, 101L, 201L,
                        "USER_TO_LEADER", 4, "更新")));

        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    private void authenticate(Long accountId, String role) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("account-" + accountId, null, java.util.List.of());
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", role);
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Review review(Long id,
                          Long projectId,
                          Long routeId,
                          Long fromAccountId,
                          Long toAccountId,
                          String reviewType,
                          Integer overallScore,
                          String content) {
        Review review = new Review();
        review.setId(id);
        review.setProjectId(projectId);
        review.setRouteId(routeId);
        review.setFromAccountId(fromAccountId);
        review.setToAccountId(toAccountId);
        review.setReviewType(reviewType);
        review.setOverallScore(overallScore);
        review.setContent(content);
        return review;
    }
}
