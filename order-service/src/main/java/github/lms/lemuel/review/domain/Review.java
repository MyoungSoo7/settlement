package github.lms.lemuel.review.domain;

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
            throw new IllegalArgumentException("평점은 1점에서 5점 사이여야 합니다.");
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────

    public Long getId()                      { return id; }
    public void setId(Long id)               { this.id = id; }

    public Long getProductId()               { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getUserId()                  { return userId; }
    public void setUserId(Long userId)       { this.userId = userId; }

    public int getRating()                   { return rating; }

    public String getContent()               { return content; }
    public void setContent(String content)   { this.content = content; }

    public LocalDateTime getCreatedAt()      { return createdAt; }
    public void setCreatedAt(LocalDateTime t){ this.createdAt = t; }

    public LocalDateTime getUpdatedAt()      { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t){ this.updatedAt = t; }
}