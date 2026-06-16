package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LoanAdvance;

public interface SaveLoanPort {
    LoanAdvance save(LoanAdvance loan);
}
