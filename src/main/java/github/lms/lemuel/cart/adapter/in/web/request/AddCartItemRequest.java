package github.lms.lemuel.cart.adapter.in.web.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull Long productId,
        @NotNull @Positive Integer quantity
) {
}
