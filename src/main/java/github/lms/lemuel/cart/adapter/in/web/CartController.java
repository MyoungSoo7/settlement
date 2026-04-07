package github.lms.lemuel.cart.adapter.in.web;

import github.lms.lemuel.cart.adapter.in.web.request.AddCartItemRequest;
import github.lms.lemuel.cart.adapter.in.web.request.UpdateCartItemRequest;
import github.lms.lemuel.cart.adapter.in.web.response.CartResponse;
import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.domain.Cart;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartUseCase cartUseCase;

    @GetMapping
    public ResponseEntity<CartResponse> getCart(@RequestParam Long userId) {
        Cart cart = cartUseCase.getOrCreateCart(userId);
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @RequestParam Long userId,
            @Valid @RequestBody AddCartItemRequest request) {
        Cart cart = cartUseCase.addItem(userId, request.productId(), request.quantity());
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @PatchMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateQuantity(
            @RequestParam Long userId,
            @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        Cart cart = cartUseCase.updateItemQuantity(userId, productId, request.quantity());
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(
            @RequestParam Long userId,
            @PathVariable Long productId) {
        Cart cart = cartUseCase.removeItem(userId, productId);
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @DeleteMapping
    public ResponseEntity<CartResponse> clearCart(@RequestParam Long userId) {
        Cart cart = cartUseCase.clearCart(userId);
        return ResponseEntity.ok(CartResponse.from(cart));
    }
}
