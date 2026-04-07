package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.review.adapter.in.web.dto.ReviewRequest;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewResponse;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewUpdateRequest;
import github.lms.lemuel.review.application.ReviewService;
import github.lms.lemuel.review.domain.Review;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 리뷰 & 평점 REST API
 * POST   /reviews                      - 리뷰 작성
 * GET    /reviews/product/{productId}  - 상품 리뷰 목록
 * GET    /reviews/user/{userId}        - 사용자 리뷰 목록
 * PATCH  /reviews/{id}                 - 리뷰 수정
 * DELETE /reviews/{id}?userId=         - 리뷰 삭제
 */
@Validated
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(@Valid @RequestBody ReviewRequest request) {
        Review review = reviewService.createReview(
                request.getProductId(),
                request.getUserId(),
                request.getRating(),
                request.getContent()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewResponse(review));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<ReviewResponse>> getProductReviews(
            @PathVariable @Positive(message = "상품 ID는 양수여야 합니다") Long productId) {
        List<ReviewResponse> reviews = reviewService.getProductReviews(productId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ReviewResponse>> getUserReviews(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<ReviewResponse> reviews = reviewService.getUserReviews(userId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ReviewResponse> updateReview(
            @PathVariable @Positive(message = "리뷰 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody ReviewUpdateRequest request) {
        Review updated = reviewService.updateReview(
                id, request.getUserId(), request.getRating(), request.getContent());
        return ResponseEntity.ok(new ReviewResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReview(
            @PathVariable @Positive(message = "리뷰 ID는 양수여야 합니다") Long id,
            @RequestParam @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        reviewService.deleteReview(id, userId);
        return ResponseEntity.noContent().build();
    }
}
