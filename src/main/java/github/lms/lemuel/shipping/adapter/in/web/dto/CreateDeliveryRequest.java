package github.lms.lemuel.shipping.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateDeliveryRequest(
        @NotNull(message = "주문 ID는 필수입니다")
        Long orderId,
        Long addressId,
        @NotBlank(message = "수령인 이름은 필수입니다")
        String recipientName,
        @NotBlank(message = "전화번호는 필수입니다")
        String phone,
        @NotBlank(message = "주소는 필수입니다")
        String address,
        @NotNull(message = "배송비는 필수입니다")
        BigDecimal shippingFee
) {}
