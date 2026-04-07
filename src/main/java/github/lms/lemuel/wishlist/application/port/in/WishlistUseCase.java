package github.lms.lemuel.wishlist.application.port.in;

import github.lms.lemuel.wishlist.domain.WishlistItem;

import java.util.List;

public interface WishlistUseCase {
    WishlistItem addItem(Long userId, Long productId);
    void removeItem(Long userId, Long productId);
    List<WishlistItem> getUserWishlist(Long userId);
    boolean isInWishlist(Long userId, Long productId);
    long getWishlistCount(Long userId);
}
