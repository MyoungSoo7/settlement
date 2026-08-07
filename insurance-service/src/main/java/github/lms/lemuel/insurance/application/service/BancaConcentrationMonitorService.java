package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.MonitorBancaConcentrationUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadBancaSalesPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/**
 * 방카슈랑스 25%룰 모니터링 배치 — 당해연도 방카 신계약을 은행 × 원수사로 집계해
 * 비중 상한 초과를 탐지한다. 판정은 {@link BancaRuleEvaluator}(순수 도메인) 몫.
 *
 * <p>위반은 차단이 아니라 <b>탐지·통보</b>다 — 이미 성립한 계약을 배치가 무를 수 없고,
 * 조치(신규 인수 중지 등)는 이벤트를 소비하는 컴플라이언스/운영의 결정이다.
 */
@Service
@Transactional(readOnly = true)
public class BancaConcentrationMonitorService implements MonitorBancaConcentrationUseCase {

    private static final Logger log = LoggerFactory.getLogger(BancaConcentrationMonitorService.class);

    private final LoadBancaSalesPort loadBancaSalesPort;
    private final PublishInsuranceEventPort publishPort;

    public BancaConcentrationMonitorService(
            LoadBancaSalesPort loadBancaSalesPort,
            PublishInsuranceEventPort publishPort) {
        this.loadBancaSalesPort = loadBancaSalesPort;
        this.publishPort = publishPort;
    }

    @Override
    @Transactional
    public BancaMonitorResult checkYear(Year year) {
        LocalDate from = year.atDay(1);
        LocalDate toExclusive = year.plusYears(1).atDay(1);

        List<BankInsurerPremium> premiums = loadBancaSalesPort.aggregateBancaPremiums(from, toExclusive);
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(premiums);

        for (BancaRuleViolation v : violations) {
            log.warn("[BancaRule] 25%룰 초과: bank={} insurer={} share={} ({} / {})",
                    v.bankCode(), v.insurerCode(), v.share(),
                    v.insurerPremiumSum().toPlainString(), v.bankPremiumTotal().toPlainString());
            publishPort.publishBancaRuleViolated(year, v);
        }
        return new BancaMonitorResult(premiums.size(), violations);
    }
}
