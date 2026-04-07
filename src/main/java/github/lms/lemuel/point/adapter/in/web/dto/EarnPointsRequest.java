package github.lms.lemuel.point.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class EarnPointsRequest {

    @NotNull(message = "사용자 ID는 필수입니다.")
    @Positive(message = "사용자 ID는 양수여야 합니다.")
    private Long userId;

    @NotNull(message = "적립 금액은 필수입니다.")
    @Positive(message = "적립 금액은 0보다 커야 합니다.")
    private BigDecimal amount;

    @NotNull(message = "설명은 필수입니다.")
    private String description;

    private String referenceType;

    private Long referenceId;
}
