package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 대출 신청: 로컬 정산뷰의 미지급 정산예정금(담보)으로 한도를 검증하고 수수료를 산정해
 * REQUESTED 상태로 등록한다. 실제 자금 실행은 {@link DisburseLoanService} 에서 재검증 후 이뤄진다.
 */
@Service
public class RequestLoanService implements RequestLoanUseCase {

    private final LoadSettlementViewPort loadSettlementViewPort;
    private final SaveLoanPort saveLoanPort;
    private final CreditPolicy creditPolicy;

    public RequestLoanService(LoadSettlementViewPort loadSettlementViewPort,
                              SaveLoanPort saveLoanPort,
                              CreditPolicy creditPolicy) {
        this.loadSettlementViewPort = loadSettlementViewPort;
        this.saveLoanPort = saveLoanPort;
        this.creditPolicy = creditPolicy;
    }

    @Override
    @Transactional
    public LoanAdvance request(RequestLoanCommand command) {
        BigDecimal unpaidSettlement = loadSettlementViewPort.sumUnpaidBySeller(command.sellerId());
        creditPolicy.validateWithinLimit(command.principal(), unpaidSettlement);

        BigDecimal fee = creditPolicy.fee(command.principal(), command.financingDays());
        LoanAdvance loan = LoanAdvance.request(command.sellerId(), command.principal(), fee);
        return saveLoanPort.save(loan);
    }
}
