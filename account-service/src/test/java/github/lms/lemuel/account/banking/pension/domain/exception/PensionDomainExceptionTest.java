package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 퇴직연금 도메인 예외의 <b>ErrorCode 매핑</b> 테스트.
 *
 * <p>ErrorCode 가 곧 HTTP 상태다(전역 핸들러가 변환). 그래서 "잘못된 요청(400)" 과
 * "상태상 불가(409/400)" 와 "남의 계약(403)" 이 뒤섞이지 않는지를 여기서 고정한다.
 * 특히 {@link PensionAccessDeniedException} 은 반드시 {@code ACCESS_DENIED} 여야 IDOR 방어가 성립한다.
 */
class PensionDomainExceptionTest {

    @Test
    void 소유자_불일치는_ACCESS_DENIED로_매핑된다() {
        PensionAccessDeniedException exception = new PensionAccessDeniedException(42L);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(exception.getPensionId()).isEqualTo(42L);
        assertThat(exception.getMessage()).contains("42");
    }

    @Test
    void 계약_미존재는_404로_매핑되고_남의_계약과_구분된다() {
        PensionNotFoundException exception = new PensionNotFoundException(7L);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RETIREMENT_PENSION_NOT_FOUND);
        assertThat(exception.getPensionId()).isEqualTo(7L);
        assertThat(exception.getErrorCode())
                .isNotEqualTo(new PensionAccessDeniedException(7L).getErrorCode());
    }

    @Test
    void 입력값_위반은_INVALID_ARGUMENT로_매핑된다() {
        assertThat(new EmployerNameRequiredException(PensionScheme.DB).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new EmployerNameNotAllowedException(PensionScheme.IRP).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new InvalidPensionRateException(new BigDecimal("1.5")).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new InvalidInvestmentInstructionException("").getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new NonPositivePensionAmountException(BigDecimal.ZERO).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new ContributionSourceNotAllowedException(PensionScheme.DB, ContributionSource.EMPLOYEE)
                .getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new PensionAmountExceedsAccumulatedException(BigDecimal.TEN, BigDecimal.ONE).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void 상태_위반은_INVALID_STATE로_매핑된다() {
        assertThat(new PensionStatusNotAllowedException(PensionStatus.CLOSED, "부담금 납입").getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(new MidWithdrawalNotPermittedException(PensionScheme.DB).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(new BenefitEligibilityNotMetException(BenefitType.ANNUITY, 54, 3).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void 예외_메시지는_원인_진단에_필요한_수치를_담는다() {
        assertThat(new ContributionSourceNotAllowedException(PensionScheme.DB, ContributionSource.EMPLOYEE)
                .getMessage()).contains("DB").contains("EMPLOYEE");
        assertThat(new BenefitEligibilityNotMetException(BenefitType.ANNUITY, 54, 3).getMessage())
                .contains("55").contains("10").contains("54").contains("3");
        assertThat(new PensionAmountExceedsAccumulatedException(BigDecimal.TEN, BigDecimal.ONE).getMessage())
                .contains("10").contains("1");
        assertThat(new MidWithdrawalNotPermittedException(PensionScheme.DB).getMessage()).contains("중도인출");
        assertThat(new NonPositivePensionAmountException(new BigDecimal("-3")).getAmount())
                .isEqualByComparingTo("-3");
        assertThat(new InvalidPensionRateException(new BigDecimal("1.5")).getRate()).isEqualByComparingTo("1.5");
        assertThat(new InvalidInvestmentInstructionException("").getProductName()).isEmpty();
        assertThat(new PensionStatusNotAllowedException(PensionStatus.CLOSED, "부담금 납입").getStatus())
                .isEqualTo(PensionStatus.CLOSED);
        assertThat(new MidWithdrawalNotPermittedException(PensionScheme.DB).getScheme())
                .isEqualTo(PensionScheme.DB);
        assertThat(new EmployerNameRequiredException(PensionScheme.DC).getScheme()).isEqualTo(PensionScheme.DC);
    }
}
