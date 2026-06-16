package github.lms.lemuel.reservation.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시공 예약 등록 요청 DTO (companyId 는 인증 토큰에서 추출).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterReservationRequest {

    // 시공 일정 / 현장 정보
    @NotNull(message = "시공 일정은 필수입니다")
    private LocalDate scheduledDate;

    @NotBlank(message = "현장 주소는 필수입니다")
    @Size(max = 300)
    private String siteAddress;

    @Size(max = 50)
    private String sitePassword;

    @NotBlank(message = "현장 담당자는 필수입니다")
    @Size(max = 100)
    private String siteManagerName;

    @NotBlank(message = "담당자 연락처는 필수입니다")
    @Pattern(regexp = "^[0-9+\\-() ]{8,30}$", message = "연락처 형식이 올바르지 않습니다")
    private String siteManagerPhone;

    // 제품 정보
    private Long productId;
    @Size(max = 100) private String woodSpecies;
    @Size(max = 100) private String brand;
    @Size(max = 200) private String productName;
    @Size(max = 50) private String productSize;

    // 시공 정보
    @NotNull(message = "시공 면적은 필수입니다")
    @Positive(message = "시공 면적은 0보다 커야 합니다")
    private BigDecimal constructionArea;

    private boolean fieldMeasured;
    private boolean expansion;
    @PositiveOrZero private BigDecimal expansionArea;
    private boolean newFloor;

    // 부자재 정보
    private boolean baseboard;
    private boolean protectionWork;
    @PositiveOrZero private BigDecimal protectionArea;

    @Size(max = 1000)
    private String note;
}
