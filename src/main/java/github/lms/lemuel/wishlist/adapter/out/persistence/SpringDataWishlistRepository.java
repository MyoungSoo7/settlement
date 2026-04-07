package github.lms.lemuel.wishlist.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataWishlistRepository extends JpaRepository<WishlistItemJpaEntity, Long> {

    List<WishlistItemJpaEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<WishlistItemJpaEntity> findByUserIdAndProductId(Long userId, Long productId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);

    long countByUserId(Long userId);

    void deleteByUserIdAndProductId(Long userId, Long productId);
}
