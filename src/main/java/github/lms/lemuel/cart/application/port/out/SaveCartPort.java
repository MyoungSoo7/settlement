package github.lms.lemuel.cart.application.port.out;

import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;

public interface SaveCartPort {

    Cart save(Cart cart);

    CartItem saveItem(CartItem item);

    void deleteItem(Long itemId);

    void deleteItemByCartIdAndProductId(Long cartId, Long productId);

    void deleteAllItemsByCartId(Long cartId);
}
