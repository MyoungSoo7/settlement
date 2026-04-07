package github.lms.lemuel.wishlist.adapter.out.persistence;

import github.lms.lemuel.wishlist.application.port.out.LoadWishlistPort;
import github.lms.lemuel.wishlist.application.port.out.SaveWishlistPort;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WishlistPersistenceAdapter implements LoadWishlistPort, SaveWishlistPort {

    private final SpringDataWishlistRepository repository;

    // ── LoadWishlistPort ────────────────────────────────────────────────

    @Override
    public List<WishlistItem> findByUserId(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(WishlistPersistenceMapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<WishlistItem> findByUserIdAndProductId(Long userId, Long productId) {
        return repository.findByUserIdAndProductId(userId, productId)
                .map(WishlistPersistenceMapper::toDomain);
    }

    @Override
    public long countByUserId(Long userId) {
        return repository.countByUserId(userId);
    }

    @Override
    public boolean existsByUserIdAndProductId(Long userId, Long productId) {
        return repository.existsByUserIdAndProductId(userId, productId);
    }

    // ── SaveWishlistPort ────────────────────────────────────────────────

    @Override
    public WishlistItem save(WishlistItem item) {
        WishlistItemJpaEntity entity = WishlistPersistenceMapper.toEntity(item);
        WishlistItemJpaEntity saved = repository.save(entity);
        return WishlistPersistenceMapper.toDomain(saved);
    }

    @Override
    public void deleteByUserIdAndProductId(Long userId, Long productId) {
        repository.deleteByUserIdAndProductId(userId, productId);
    }
}
