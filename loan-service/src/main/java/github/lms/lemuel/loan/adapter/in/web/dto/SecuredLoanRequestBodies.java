package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.RepaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 요청 바디 모음.
 *
 * <p><b>차주 식별자가 바디에 없다</b> — JWT 주체에서 파생하기 때문이다(IDOR 가드레일). 요청이 차주를
 * 지정할 수 있으면 타인 명의 대출을 신청할 수 있게 된다.
 */
public final class SecuredLoanRequestBodies {

    private SecuredLoanRequestBodies() {
    }

    /**
     * 주택담보대출 신청.
     *
     * @param registrationNo          사업자등록번호(선택) — 있으면 법인 차주로 해석된다
     * @param declaredCollateralValue 제시 담보 평가액. Phase 1 은 이 값이 그대로 확정되며,
     *                                Phase 2 에서 위성 서비스 조회값으로 대체된다
     */
    public record MortgageRequestBody(
            @NotBlank String borrowerName,
            String registrationNo,
            @NotBlank String collateralDescription,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal declaredCollateralValue,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal principal,
            @Min(1) int termMonths,
            @NotNull RepaymentMethod repaymentMethod) {
    }

    /**
     * 개인신용대출 신청.
     *
     * @param cbScore 외부 CB 점수(0~1000) — 담보가 없어 유일한 심사 근거이며 스냅샷으로 보존된다
     */
    public record PersonalCreditRequestBody(
            @NotBlank String borrowerName,
            String registrationNo,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal principal,
            @Min(1) int termMonths,
            @NotNull RepaymentMethod repaymentMethod,
            @Min(0) int cbScore) {
    }

    /**
     * 회차 상환. 원금·이자를 <b>분리해</b> 받는다 — 회계상 계정 흐름이 달라 합계만 받으면 서버가 임의로
     * 쪼개야 하고, 그러면 상환표와 원장이 어긋난다.
     */
    public record SecuredLoanRepayRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal principalPortion,
            @NotNull @DecimalMin("0") BigDecimal interestPortion) {
    }

    /**
     * 중도상환. 이자 필드가 없다 — 회차 이자와 별개로 원금만 앞당겨 갚는 경로이며,
     * 수수료는 서버가 정책(부과기간 1095일 잔여 비례·경과 3년 면제)으로 산정해 응답에 알려준다.
     */
    public record SecuredLoanPrepayRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
    }
}
