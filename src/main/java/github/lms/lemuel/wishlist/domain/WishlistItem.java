package github.lms.lemuel.wishlist.domain;

import java.time.LocalDateTime;

/**
 * 위시리스트 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class WishlistItem {

    private Long id;
    private Long userId;
    private Long productId;
    private LocalDateTime createdAt;

    public WishlistItem() {}

    public static WishlistItem create(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("유저 ID는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("상품 ID는 필수입니다.");
        }
        WishlistItem item = new WishlistItem();
        item.userId = userId;
        item.productId = productId;
        item.createdAt = LocalDateTime.now();
        return item;
    }

    // ── Getters & Setters ──────────────────────────────────────────────

    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }

    public Long getUserId()                      { return userId; }
    public void setUserId(Long userId)           { this.userId = userId; }

    public Long getProductId()                   { return productId; }
    public void setProductId(Long productId)     { this.productId = productId; }

    public LocalDateTime getCreatedAt()          { return createdAt; }
    public void setCreatedAt(LocalDateTime t)    { this.createdAt = t; }
}
