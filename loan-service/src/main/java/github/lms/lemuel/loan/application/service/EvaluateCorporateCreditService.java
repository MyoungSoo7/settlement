package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import github.lms.lemuel.loan.domain.CorporateLoanNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 상장사 신용평가·한도 조회. financial 재무제표 + 로컬 평판 프로젝션으로 신용점수/등급/한도를 산정해
 * 그대로 반환한다(부수효과 없음 — 대출 생성은 {@link RequestCorporateLoanService} 의 책임).
 */
@Service
public class EvaluateCorporateCreditService implements EvaluateCorporateCreditUseCase {

    private final LoadCorporateFinancialPort loadCorporateFinancialPort;
    private final LoadCompanyReputationPort loadCompanyReputationPort;
    private final CorporateCreditPolicy creditPolicy;

    public EvaluateCorporateCreditService(LoadCorporateFinancialPort loadCorporateFinancialPort,
                                          LoadCompanyReputationPort loadCompanyReputationPort,
                                          CorporateCreditPolicy creditPolicy) {
        this.loadCorporateFinancialPort = loadCorporateFinancialPort;
        this.loadCompanyReputationPort = loadCompanyReputationPort;
        this.creditPolicy = creditPolicy;
    }

    @Override
    public CorporateCreditView evaluate(String stockCode) {
        CorporateFinancials fin = loadCorporateFinancialPort.loadLatest(stockCode)
                .orElseThrow(() -> new CorporateLoanNotFoundException(
                        "상장사 재무자료를 찾을 수 없습니다: " + stockCode));
        String reputationGrade = loadCompanyReputationPort.findByStockCode(stockCode)
                .map(r -> r.getGrade())
                .orElse(null);

        int score = creditPolicy.creditScore(fin, reputationGrade);
        String grade = creditPolicy.creditGrade(score);
        BigDecimal limit = creditPolicy.creditLimit(fin.totalEquity(), grade);

        return new CorporateCreditView(
                fin.stockCode(),
                fin.corpName(),
                fin.market(),
                fin.fiscalYear(),
                score,
                grade,
                limit,
                fin.debtRatio(),
                fin.operatingMargin(),
                fin.roa(),
                reputationGrade);
    }
}
