package github.lms.lemuel.cart.application.port.in;

import github.lms.lemuel.cart.domain.Cart;

public interface CartUseCase {

    Cart getOrCreateCart(Long userId);

    Cart addItem(Long userId, Long productId, int quantity);

    Cart updateItemQuantity(Long userId, Long productId, int quantity);

    Cart removeItem(Long userId, Long productId);

    Cart clearCart(Long userId);

    Cart getCart(Long userId);
}
