package github.lms.lemuel.shipping.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDeliveryStatusRequest(
        @NotBlank(message = "배송 상태는 필수입니다")
        String status
) {}
