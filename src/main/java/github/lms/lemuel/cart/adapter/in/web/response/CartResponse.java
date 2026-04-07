package github.lms.lemuel.cart.adapter.in.web.response;

import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public record CartResponse(
        Long id,
        Long userId,
        CartStatus status,
        List<CartItemResponse> items,
        BigDecimal totalAmount,
        int totalItemCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CartResponse from(Cart cart) {
        List<CartItemResponse> itemResponses = cart.getItems().stream()
                .map(CartItemResponse::from)
                .collect(Collectors.toList());

        return new CartResponse(
                cart.getId(),
                cart.getUserId(),
                cart.getStatus(),
                itemResponses,
                cart.getTotalAmount(),
                cart.getTotalItemCount(),
                cart.getCreatedAt(),
                cart.getUpdatedAt()
        );
    }
}
