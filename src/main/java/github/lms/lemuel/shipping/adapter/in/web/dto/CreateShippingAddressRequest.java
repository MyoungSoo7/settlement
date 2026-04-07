package github.lms.lemuel.shipping.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateShippingAddressRequest(
        @NotNull(message = "사용자 ID는 필수입니다")
        Long userId,
        @NotBlank(message = "수령인 이름은 필수입니다")
        String recipientName,
        @NotBlank(message = "전화번호는 필수입니다")
        String phone,
        @NotBlank(message = "우편번호는 필수입니다")
        String zipCode,
        @NotBlank(message = "주소는 필수입니다")
        String address,
        String addressDetail
) {}
