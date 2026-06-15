package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 대출 실행(선지급).
 *
 * <p>신청 시점과 실행 시점 사이에 담보(정산예정금)가 변할 수 있으므로, 실행 직전에
 * 비관적 락으로 미지급 합계를 재조회해 한도를 재검증한다(동시 선지급 경합 직렬화).
 * 통과 시 DISBURSED 로 전이하고 LoanDisbursementRequested 를 Outbox 에 기록 →
 * settlement 가 payout 으로 셀러에게 실제 송금한다.
 */
@Service
public class DisburseLoanService implements DisburseLoanUseCase {

    private final LoadLoanPort loadLoanPort;
    private final SaveLoanPort saveLoanPort;
    private final LoadSettlementViewPort loadSettlementViewPort;
    private final CreditPolicy creditPolicy;
    private final PublishLoanEventPort publishLoanEventPort;

    public DisburseLoanService(LoadLoanPort loadLoanPort,
                               SaveLoanPort saveLoanPort,
                               LoadSettlementViewPort loadSettlementViewPort,
                               CreditPolicy creditPolicy,
                               PublishLoanEventPort publishLoanEventPort) {
        this.loadLoanPort = loadLoanPort;
        this.saveLoanPort = saveLoanPort;
        this.loadSettlementViewPort = loadSettlementViewPort;
        this.creditPolicy = creditPolicy;
        this.publishLoanEventPort = publishLoanEventPort;
    }

    @Override
    @Transactional
    public LoanAdvance disburse(Long loanId) {
        LoanAdvance loan = loadLoanPort.load(loanId);
        loan.approve();

        // 실행 직전 담보 재검증 (비관적 락으로 동시 실행 직렬화)
        BigDecimal freshUnpaid = loadSettlementViewPort.sumUnpaidBySellerForUpdate(loan.getSellerId());
        try {
            creditPolicy.validateWithinLimit(loan.getPrincipal(), freshUnpaid);
        } catch (IllegalArgumentException e) {
            loan.reject();
            saveLoanPort.save(loan);
            throw new IllegalStateException("실행 시점 담보 부족으로 대출이 거절되었습니다. loanId=" + loanId, e);
        }

        loan.disburse();
        LoanAdvance saved = saveLoanPort.save(loan);
        publishLoanEventPort.publishDisbursementRequested(saved);
        return saved;
    }
}
