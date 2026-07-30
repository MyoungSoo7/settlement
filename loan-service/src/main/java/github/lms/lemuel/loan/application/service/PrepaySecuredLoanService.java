package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanPolicy;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 담보/개인신용 대출 중도상환.
 *
 * <p>회차 상환({@link RepaySecuredLoanService})과 달리 이자 납입이 없고 <b>중도상환수수료</b>가 붙는다.
 * 수수료 산식(부과기간 1095일 잔여 비례·경과 3년 면제)은 정책의 정적 순수 함수라 기준금리 조달이 필요 없다 —
 * 중도상환 경로가 economics-service 장애에 결합되면 안 되기 때문이다(Phase 2 기준금리 연동 대비).
 *
 * <p>잔존일수 산정은 도메인의 실행 시각 스냅샷({@code disbursedAt})을 기산점으로 쓰고, "지금"은
 * 응용 계층의 {@link Clock} 에서 온다 — 수수료가 시각에 민감하므로 테스트 결정성을 위해 도메인은
 * 시각을 만들지 않는다.
 *
 * <p><b>소유권 대조(IDOR 방지)</b>: 상환 주체는 JWT 에서 파생되며 불일치는 403. 웹 어댑터와 이중 방어.
 */
@Service
public class PrepaySecuredLoanService implements PrepaySecuredLoanUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final SaveSecuredLoanPort saveSecuredLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishSecuredLoanEventPort publishSecuredLoanEventPort;
    private final LoanMetricsPort loanMetricsPort;
    private final Clock clock;

    public PrepaySecuredLoanService(LoadSecuredLoanPort loadSecuredLoanPort,
                                    SaveSecuredLoanPort saveSecuredLoanPort,
                                    AppendLedgerPort appendLedgerPort,
                                    PublishSecuredLoanEventPort publishSecuredLoanEventPort,
                                    LoanMetricsPort loanMetricsPort,
                                    Clock clock) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.saveSecuredLoanPort = saveSecuredLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishSecuredLoanEventPort = publishSecuredLoanEventPort;
        this.loanMetricsPort = loanMetricsPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PrepayResult prepay(Long loanId, Long requesterUserId, BigDecimal amount) {
        // 동시 중도상환·회차상환 요청을 직렬화한다 — 잔액 이중 차감·전표 중복 방어.
        SecuredLoan loan = loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
        requireOwner(loan, requesterUserId);

        BigDecimal deducted = loan.prepay(amount);
        // 수수료는 실제 차감액 기준이다 — 요청액이 잔액을 넘으면 clamp 된 금액에만 부과해야
        // 과다 청구가 없다. 경과일수는 차감 이전이든 이후든 동일(상태와 무관한 날짜 계산).
        BigDecimal fee = SecuredLoanPolicy.earlyRepaymentFee(
                deducted, loan.elapsedDays(LocalDateTime.now(clock)));

        releaseCollateralIfSettled(loan);
        SecuredLoan saved = saveSecuredLoanPort.save(loan);

        appendLedgerPort.append(LoanLedgerEntry.securedPrincipalRepayment(saved.getId(), deducted));
        if (fee.signum() > 0) {
            // 면제(경과 3년)·0원 수수료는 기표하지 않는다 — 원장 전표는 양수 금액 불변식이 있다.
            appendLedgerPort.append(LoanLedgerEntry.securedEarlyRepaymentFee(saved.getId(), fee));
        }
        if (saved.getStatus() == SecuredLoanStatus.REPAID) {
            // 중도상환에는 회차 이자가 없다 — 완제 이벤트의 이자 합계는 0. 수수료는 이 완제에 부과된 실액.
            publishSecuredLoanEventPort.publishRepaid(saved, BigDecimal.ZERO, fee);
        }
        loanMetricsPort.securedRepaid(deducted);
        return new PrepayResult(saved, deducted, fee);
    }

    /** 완제되면 담보를 말소한다 — 상환이 끝났는데 담보가 유효로 남아 있으면 안 된다. */
    private void releaseCollateralIfSettled(SecuredLoan loan) {
        if (loan.getStatus() != SecuredLoanStatus.REPAID) {
            return;
        }
        Collateral collateral = loan.getCollateral();
        if (collateral != null && collateral.getStatus() != CollateralStatus.RELEASED) {
            collateral.release();
            saveSecuredLoanPort.saveCollateral(collateral);
        }
    }

    private static void requireOwner(SecuredLoan loan, Long requesterUserId) {
        if (requesterUserId == null || !requesterUserId.equals(loan.getBorrower().userId())) {
            throw new AccessDeniedException("본인 소유가 아닌 대출입니다. loanId=" + loan.getId());
        }
    }
}
