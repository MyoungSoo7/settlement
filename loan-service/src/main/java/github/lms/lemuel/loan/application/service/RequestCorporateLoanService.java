package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.exception.CorporateLoanRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 기업 신용대출 신청. 신용평가(재무 + 평판) → E등급/한도초과/재무없음 거절(422) → 수수료 산정 →
 * REQUESTED 로 등록한다. 신용점수/등급은 신청 시점 스냅샷으로 보존한다(이후 재무·평판 변동 무관 재현성).
 * 실제 자금 지급은 {@link DisburseCorporateLoanService} 에서 이뤄진다.
 */
@Service
public class RequestCorporateLoanService implements RequestCorporateLoanUseCase {

    private final LoadCorporateFinancialPort loadCorporateFinancialPort;
    private final LoadCompanyReputationPort loadCompanyReputationPort;
    private final SaveCorporateLoanPort saveCorporateLoanPort;
    private final CorporateCreditPolicy creditPolicy;

    public RequestCorporateLoanService(LoadCorporateFinancialPort loadCorporateFinancialPort,
                                       LoadCompanyReputationPort loadCompanyReputationPort,
                                       SaveCorporateLoanPort saveCorporateLoanPort,
                                       CorporateCreditPolicy creditPolicy) {
        this.loadCorporateFinancialPort = loadCorporateFinancialPort;
        this.loadCompanyReputationPort = loadCompanyReputationPort;
        this.saveCorporateLoanPort = saveCorporateLoanPort;
        this.creditPolicy = creditPolicy;
    }

    @Override
    @Transactional
    public CorporateLoan request(RequestCorporateLoanCommand command) {
        CorporateFinancials fin = loadCorporateFinancialPort.loadLatest(command.stockCode())
                .orElseThrow(() -> new CorporateLoanRejectedException(
                        "상장사 재무자료가 없어 신용평가를 할 수 없습니다: " + command.stockCode()));
        String reputationGrade = loadCompanyReputationPort.findByStockCode(command.stockCode())
                .map(r -> r.getGrade())
                .orElse(null);

        int score = creditPolicy.creditScore(fin, reputationGrade);
        String grade = creditPolicy.creditGrade(score);
        if (creditPolicy.isLoanBlocked(grade)) {
            throw new CorporateLoanRejectedException(
                    "신용등급 E — 기업 신용대출 불가 (score=" + score + ")");
        }

        BigDecimal limit = creditPolicy.creditLimit(fin.totalEquity(), grade);
        if (command.principal().compareTo(limit) > 0) {
            throw new CorporateLoanRejectedException(
                    "신청액이 한도를 초과합니다. requested=" + command.principal() + ", limit=" + limit
                            + " (grade=" + grade + ")",
                    command.principal(), limit);
        }

        BigDecimal fee = creditPolicy.fee(command.principal(), command.termDays(), grade);
        CorporateLoan loan = CorporateLoan.request(
                fin.stockCode(), fin.corpName(), command.principal(), fee,
                command.termDays(), score, grade);
        return saveCorporateLoanPort.save(loan);
    }
}
