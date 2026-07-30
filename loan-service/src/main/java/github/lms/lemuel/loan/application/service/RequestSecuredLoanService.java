package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.out.BaseRatePort;
import github.lms.lemuel.loan.application.port.out.CollateralValuationPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanPolicy;
import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 담보/개인신용 대출 신청·심사.
 *
 * <p>신청 시점에 심사까지 끝낸다 — 한도 검증과 금리 확정이 여기서 일어나고, 확정된 연이율은
 * 애그리거트에 <b>스냅샷으로 박힌다</b>. 이후 기준금리가 변해도 이미 신청된 대출의 조건은 흔들리지 않는다.
 *
 * <p>기준금리는 {@link BaseRatePort} 에서 <b>매 신청마다</b> 조달한다(정책 객체를 빈으로 고정하지 않는
 * 이유). Phase 1 구현체는 설정값을 돌려주지만 Phase 2 에서 economics-service 연동으로 바뀌면 값이
 * 시점마다 달라지므로, 정책을 기동 시점에 한 번 만들어 두면 낡은 금리로 심사하게 된다.
 *
 * <p>담보는 <b>한도 검증을 통과한 뒤에</b> 저장한다 — 거절된 신청이 고아 담보 행을 남기지 않게 하려는
 * 것으로, 트랜잭션 롤백에 의존하지 않고 순서로 보장한다.
 */
@Service
public class RequestSecuredLoanService implements RequestSecuredLoanUseCase {

    private final SaveSecuredLoanPort saveSecuredLoanPort;
    private final CollateralValuationPort collateralValuationPort;
    private final BaseRatePort baseRatePort;
    private final LoanMetricsPort loanMetricsPort;
    private final BigDecimal realEstateLtvRatio;
    private final Clock clock;

    public RequestSecuredLoanService(SaveSecuredLoanPort saveSecuredLoanPort,
                                     CollateralValuationPort collateralValuationPort,
                                     BaseRatePort baseRatePort,
                                     LoanMetricsPort loanMetricsPort,
                                     @Value("${app.loan.secured.real-estate-ltv:0.70}")
                                     BigDecimal realEstateLtvRatio,
                                     Clock clock) {
        this.saveSecuredLoanPort = saveSecuredLoanPort;
        this.collateralValuationPort = collateralValuationPort;
        this.baseRatePort = baseRatePort;
        this.loanMetricsPort = loanMetricsPort;
        this.realEstateLtvRatio = realEstateLtvRatio;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SecuredLoan requestMortgage(MortgageCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);
        SecuredLoanPolicy policy = policy();

        BigDecimal appraised = collateralValuationPort.appraise(CollateralType.REAL_ESTATE,
                command.collateralDescription(), command.declaredCollateralValue());
        Collateral collateral = Collateral.pledge(CollateralType.REAL_ESTATE,
                command.collateralDescription(), appraised, now);

        // 한도 = 유효담보가치 × LTV. 초과하면 담보를 저장하기 전에 거절한다.
        validateOrCountRejection(policy, command.principal(),
                policy.mortgageLimit(collateral.effectiveValue(), CollateralType.REAL_ESTATE));

        Collateral savedCollateral = saveSecuredLoanPort.saveCollateral(collateral);
        SecuredLoan loan = SecuredLoan.requestMortgage(
                borrowerOf(command.borrowerUserId(), command.borrowerName(), command.registrationNo()),
                savedCollateral, command.principal(), command.termMonths(),
                policy.annualRatePercent(LoanProductType.MORTGAGE, null),
                command.repaymentMethod(), now);
        SecuredLoan saved = saveSecuredLoanPort.save(loan);
        loanMetricsPort.securedRequested();
        return saved;
    }

    @Override
    @Transactional
    public SecuredLoan requestPersonalCredit(PersonalCreditCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);
        SecuredLoanPolicy policy = policy();

        String grade = policy.creditGrade(command.cbScore());
        if (policy.isLoanBlocked(grade)) {
            loanMetricsPort.securedRejected();
            throw new SecuredLoanRejectedException(
                    "신용등급이 대출 가능 기준에 미달합니다: 점수 " + command.cbScore() + " / 등급 " + grade);
        }
        validateOrCountRejection(policy, command.principal(), policy.personalCreditLimit(grade));

        SecuredLoan loan = SecuredLoan.requestPersonalCredit(
                borrowerOf(command.borrowerUserId(), command.borrowerName(), command.registrationNo()),
                command.principal(), command.termMonths(),
                policy.annualRatePercent(LoanProductType.PERSONAL_CREDIT, grade),
                command.repaymentMethod(), command.cbScore(), grade, now);
        SecuredLoan saved = saveSecuredLoanPort.save(loan);
        loanMetricsPort.securedRequested();
        return saved;
    }

    /** 한도 검증 — 거절은 관측 지표로 계상한 뒤 그대로 전파한다(거절률 산정 분자). */
    private void validateOrCountRejection(SecuredLoanPolicy policy, BigDecimal requested, BigDecimal limit) {
        try {
            policy.validateWithinLimit(requested, limit);
        } catch (SecuredLoanRejectedException ex) {
            loanMetricsPort.securedRejected();
            throw ex;
        }
    }

    /** 신청 시점의 기준금리로 정책을 구성한다(금리 스냅샷의 출처). */
    private SecuredLoanPolicy policy() {
        return new SecuredLoanPolicy(baseRatePort.currentBaseRatePercent(), realEstateLtvRatio);
    }

    /** 사업자등록번호 유무로 차주 유형을 해석한다 — 유형을 요청에서 직접 받지 않아 불일치 입력을 원천 차단. */
    private static Borrower borrowerOf(Long userId, String name, String registrationNo) {
        return registrationNo == null || registrationNo.isBlank()
                ? Borrower.individual(userId, name)
                : Borrower.corporate(userId, name, registrationNo);
    }
}
