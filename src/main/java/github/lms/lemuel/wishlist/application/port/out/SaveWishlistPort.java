package github.lms.lemuel.wishlist.application.port.out;

import github.lms.lemuel.wishlist.domain.WishlistItem;

public interface SaveWishlistPort {
    WishlistItem save(WishlistItem item);
    void deleteByUserIdAndProductId(Long userId, Long productId);
}
