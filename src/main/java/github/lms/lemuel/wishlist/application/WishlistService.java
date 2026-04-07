package github.lms.lemuel.wishlist.application;

import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistPort;
import github.lms.lemuel.wishlist.application.port.out.SaveWishlistPort;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WishlistService implements WishlistUseCase {

    private final LoadWishlistPort loadWishlistPort;
    private final SaveWishlistPort saveWishlistPort;

    @Override
    public WishlistItem addItem(Long userId, Long productId) {
        log.info("위시리스트 추가 시작: userId={}, productId={}", userId, productId);

        if (loadWishlistPort.existsByUserIdAndProductId(userId, productId)) {
            throw new IllegalStateException("이미 위시리스트에 추가된 상품입니다.");
        }

        WishlistItem item = WishlistItem.create(userId, productId);
        WishlistItem saved = saveWishlistPort.save(item);
        log.info("위시리스트 추가 완료: id={}", saved.getId());
        return saved;
    }

    @Override
    public void removeItem(Long userId, Long productId) {
        log.info("위시리스트 삭제 시작: userId={}, productId={}", userId, productId);
        saveWishlistPort.deleteByUserIdAndProductId(userId, productId);
        log.info("위시리스트 삭제 완료: userId={}, productId={}", userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WishlistItem> getUserWishlist(Long userId) {
        return loadWishlistPort.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInWishlist(Long userId, Long productId) {
        return loadWishlistPort.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getWishlistCount(Long userId) {
        return loadWishlistPort.countByUserId(userId);
    }
}
