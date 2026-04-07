package github.lms.lemuel.wishlist.application.port.out;

import github.lms.lemuel.wishlist.domain.WishlistItem;

import java.util.List;
import java.util.Optional;

public interface LoadWishlistPort {
    List<WishlistItem> findByUserId(Long userId);
    Optional<WishlistItem> findByUserIdAndProductId(Long userId, Long productId);
    long countByUserId(Long userId);
    boolean existsByUserIdAndProductId(Long userId, Long productId);
}
