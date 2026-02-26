package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.review.adapter.in.web.dto.ReviewRequest;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewResponse;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewUpdateRequest;
import github.lms.lemuel.review.application.ReviewService;
import github.lms.lemuel.review.domain.Review;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 리뷰 & 평점 REST API
 * POST   /reviews                      - 리뷰 작성
 * GET    /reviews/product/{productId}  - 상품 리뷰 목록
 * GET    /reviews/user/{userId}        - 사용자 리뷰 목록
 * PUT    /reviews/{id}                 - 리뷰 수정
 * DELETE /reviews/{id}?userId=         - 리뷰 삭제
 */
@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public ResponseEntity<?> createReview(@Valid @RequestBody ReviewRequest request) {
        try {
            Review review = reviewService.createReview(
                    request.getProductId(),
                    request.getUserId(),
                    request.getRating(),
                    request.getContent()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewResponse(review));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<ReviewResponse>> getProductReviews(@PathVariable Long productId) {
        List<ReviewResponse> reviews = reviewService.getProductReviews(productId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ReviewResponse>> getUserReviews(@PathVariable Long userId) {
        List<ReviewResponse> reviews = reviewService.getUserReviews(userId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateReview(
            @PathVariable Long id,
            @Valid @RequestBody ReviewUpdateRequest request) {
        try {
            Review updated = reviewService.updateReview(
                    id, request.getUserId(), request.getRating(), request.getContent());
            return ResponseEntity.ok(new ReviewResponse(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteReview(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            reviewService.deleteReview(id, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }
}