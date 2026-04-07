package github.lms.lemuel.cart.application.port.out;

import github.lms.lemuel.cart.domain.Cart;

import java.util.Optional;

public interface LoadCartPort {

    Optional<Cart> findActiveByUserId(Long userId);

    Optional<Cart> findById(Long id);
}
