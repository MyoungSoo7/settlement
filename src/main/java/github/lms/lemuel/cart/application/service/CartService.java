package github.lms.lemuel.cart.application.service;

import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CartService implements CartUseCase {

    private final LoadCartPort loadCartPort;
    private final SaveCartPort saveCartPort;
    private final LoadProductPort loadProductPort;

    @Override
    @Transactional(readOnly = true)
    public Cart getCart(Long userId) {
        log.info("장바구니 조회: userId={}", userId);
        return loadCartPort.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Active cart not found for user: " + userId));
    }

    @Override
    public Cart getOrCreateCart(Long userId) {
        log.info("장바구니 조회 또는 생성: userId={}", userId);
        return loadCartPort.findActiveByUserId(userId)
                .orElseGet(() -> {
                    log.info("새 장바구니 생성: userId={}", userId);
                    Cart newCart = Cart.create(userId);
                    return saveCartPort.save(newCart);
                });
    }

    @Override
    public Cart addItem(Long userId, Long productId, int quantity) {
        log.info("장바구니 아이템 추가: userId={}, productId={}, quantity={}", userId, productId, quantity);

        // 1. 상품 조회 및 가격 스냅샷
        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (!product.isAvailableForSale()) {
            throw new IllegalStateException("Product is not available for sale: " + productId);
        }

        // 2. 장바구니 조회 또는 생성
        Cart cart = getOrCreateCart(userId);

        // 3. 도메인 로직으로 아이템 추가
        CartItem item = cart.addItem(productId, quantity, product.getPrice());
        item.setCartId(cart.getId());

        // 4. 아이템 저장
        saveCartPort.saveItem(item);

        // 5. 최신 상태 반환
        return loadCartPort.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Cart not found after adding item"));
    }

    @Override
    public Cart updateItemQuantity(Long userId, Long productId, int quantity) {
        log.info("장바구니 아이템 수량 변경: userId={}, productId={}, quantity={}", userId, productId, quantity);

        // 1. 장바구니 조회
        Cart cart = getCart(userId);

        // 2. 도메인 로직으로 수량 변경
        CartItem item = cart.updateItemQuantity(productId, quantity);

        // 3. 아이템 저장
        saveCartPort.saveItem(item);

        // 4. 최신 상태 반환
        return loadCartPort.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Cart not found after updating item"));
    }

    @Override
    public Cart removeItem(Long userId, Long productId) {
        log.info("장바구니 아이템 제거: userId={}, productId={}", userId, productId);

        // 1. 장바구니 조회
        Cart cart = getCart(userId);

        // 2. 도메인 로직으로 아이템 제거 (검증)
        cart.removeItem(productId);

        // 3. DB에서 삭제
        saveCartPort.deleteItemByCartIdAndProductId(cart.getId(), productId);

        // 4. 최신 상태 반환
        return loadCartPort.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Cart not found after removing item"));
    }

    @Override
    public Cart clearCart(Long userId) {
        log.info("장바구니 비우기: userId={}", userId);

        // 1. 장바구니 조회
        Cart cart = getCart(userId);

        // 2. 도메인 로직으로 검증
        cart.clearItems();

        // 3. DB에서 모든 아이템 삭제
        saveCartPort.deleteAllItemsByCartId(cart.getId());

        // 4. 최신 상태 반환
        return loadCartPort.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Cart not found after clearing"));
    }
}
