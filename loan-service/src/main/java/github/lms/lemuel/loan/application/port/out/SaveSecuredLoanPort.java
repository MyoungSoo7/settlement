package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.SecuredLoan;

/**
 * 담보/개인신용 대출 저장 아웃바운드 포트. 담보는 대출과 생명주기가 달라 별도로 저장한다.
 */
public interface SaveSecuredLoanPort {

    SecuredLoan save(SecuredLoan loan);

    /** 담보 저장(신규 설정·상태 전이 반영). */
    Collateral saveCollateral(Collateral collateral);
}
