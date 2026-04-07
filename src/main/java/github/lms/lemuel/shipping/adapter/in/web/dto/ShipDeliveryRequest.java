package github.lms.lemuel.shipping.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipDeliveryRequest(
        @NotBlank(message = "운송장 번호는 필수입니다")
        String trackingNumber,
        @NotBlank(message = "택배사는 필수입니다")
        String carrier
) {}
