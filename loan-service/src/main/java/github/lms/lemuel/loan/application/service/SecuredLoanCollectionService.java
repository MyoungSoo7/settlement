package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ManageSecuredLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담보/개인신용 대출 연체 관리(연체 진입·기한이익상실).
 *
 * <p>전이 가능 여부는 전적으로 도메인 상태머신이 판정한다 — 이 서비스는 락 조회와 저장만 맡는다.
 * 특히 {@code DISBURSED → DEFAULTED} 직행 차단은 여기서 if 문으로 흉내내지 않는다.
 */
@Service
public class SecuredLoanCollectionService implements ManageSecuredLoanCollectionUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final SaveSecuredLoanPort saveSecuredLoanPort;

    public SecuredLoanCollectionService(LoadSecuredLoanPort loadSecuredLoanPort,
                                        SaveSecuredLoanPort saveSecuredLoanPort) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.saveSecuredLoanPort = saveSecuredLoanPort;
    }

    @Override
    @Transactional
    public SecuredLoan markOverdue(Long loanId) {
        SecuredLoan loan = require(loanId);
        loan.markOverdue();
        return saveSecuredLoanPort.save(loan);
    }

    @Override
    @Transactional
    public SecuredLoan accelerate(Long loanId) {
        SecuredLoan loan = require(loanId);
        loan.accelerate();
        return saveSecuredLoanPort.save(loan);
    }

    private SecuredLoan require(Long loanId) {
        return loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException("담보대출을 찾을 수 없습니다. loanId=" + loanId));
    }
}
