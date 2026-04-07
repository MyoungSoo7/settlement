package github.lms.lemuel.wishlist.adapter.in.web.dto;

import github.lms.lemuel.wishlist.domain.WishlistItem;

public record WishlistItemResponse(
        Long id,
        Long userId,
        Long productId,
        String createdAt
) {
    public static WishlistItemResponse from(WishlistItem domain) {
        return new WishlistItemResponse(
                domain.getId(),
                domain.getUserId(),
                domain.getProductId(),
                domain.getCreatedAt() != null ? domain.getCreatedAt().toString() : null
        );
    }
}
