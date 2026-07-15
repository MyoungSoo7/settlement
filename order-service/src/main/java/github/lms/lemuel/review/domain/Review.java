package github.lms.lemuel.review.domain;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 리뷰 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class Review {

    private Long id;
    private Long productId;
    private Long userId;
    private int rating;      // 1 ~ 5
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Review() {}

    public static Review create(Long productId, Long userId, int rating, String content) {
        validateRating(rating);
        Review review = new Review();
        review.productId = productId;
        review.userId    = userId;
        review.rating    = rating;
        review.content   = content;
        review.createdAt = LocalDateTime.now();
        review.updatedAt = LocalDateTime.now();
        return review;
    }

    public void update(int rating, String content) {
        validateRating(rating);
        this.rating    = rating;
        this.content   = content;
        this.updatedAt = LocalDateTime.now();
    }

    private static void validateRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new ReviewInvariantViolationException("평점은 1점에서 5점 사이여야 합니다.");
        }
    }

    /**
     * 영속 레코드 복원 팩토리 — no-arg + setter 대신 이 경로로만 도메인을 재구성한다. 평점 규칙은 유지 검증.
     */
    public static Review rehydrate(Long id, Long productId, Long userId, int rating, String content,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        validateRating(rating);
        Review review = new Review();
        review.id        = id;
        review.productId = productId;
        review.userId    = userId;
        review.rating    = rating;
        review.content   = content;
        review.createdAt = createdAt;
        review.updatedAt = updatedAt;
        return review;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id)            { this.id = id; }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId()                      { return id; }

    public Long getProductId()               { return productId; }

    public Long getUserId()                  { return userId; }

    public int getRating()                   { return rating; }

    public String getContent()               { return content; }

    public LocalDateTime getCreatedAt()      { return createdAt; }

    public LocalDateTime getUpdatedAt()      { return updatedAt; }
}