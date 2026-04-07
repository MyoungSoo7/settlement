package github.lms.lemuel.seller.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateBankInfoRequest(
        @NotBlank(message = "은행명은 필수입니다.")
        String bankName,

        @NotBlank(message = "계좌번호는 필수입니다.")
        String accountNumber,

        @NotBlank(message = "예금주명은 필수입니다.")
        String accountHolder
) {}
