package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.in.PayRequestedGeneralPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadGeneralPayoutPort;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveGeneralPayoutPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 일반지급 실행 배치 + 내역 조회 (§14).
 *
 * <p>배치는 REQUESTED 전건을 지급한다 — payout 은 이미 확정된 지급 의무라 보류 개념이 없다
 * (수수료 지급 배치의 ACTIVE 게이트와 다른 점 — 계약은 이미 terminal 이다).
 * 지급 1건당 {@code general_payout_paid} 를 같은 tx 의 Outbox 에 기록한다.
 * 이중 지급은 도메인 전이 가드(REQUESTED→PAID 만 허용)가 차단한다.
 */
@Service
@Transactional
public class GeneralPayoutService implements PayRequestedGeneralPayoutsUseCase, GetPolicyPayoutsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GeneralPayoutService.class);

    private final LoadGeneralPayoutPort loadPayoutPort;
    private final SaveGeneralPayoutPort savePayoutPort;
    private final LoadPolicyPort loadPolicyPort;
    private final PublishInsuranceEventPort publishPort;

    public GeneralPayoutService(
            LoadGeneralPayoutPort loadPayoutPort,
            SaveGeneralPayoutPort savePayoutPort,
            LoadPolicyPort loadPolicyPort,
            PublishInsuranceEventPort publishPort) {
        this.loadPayoutPort = loadPayoutPort;
        this.savePayoutPort = savePayoutPort;
        this.loadPolicyPort = loadPolicyPort;
        this.publishPort = publishPort;
    }

    @Override
    public GeneralPayoutBatchResult payRequestedOn(LocalDate today) {
        int paid = 0;
        for (GeneralPayout payout : loadPayoutPort.findRequested()) {
            payout.markPaid(today);  // D-G4 전이 가드 — 이중 지급 차단
            savePayoutPort.save(payout);
            publishPort.publishGeneralPayoutPaid(payout);
            paid++;
        }
        if (paid > 0) {
            log.info("[GeneralPayoutBatch] today={} 지급={}", today, paid);
        }
        return new GeneralPayoutBatchResult(paid);
    }

    @Override
    public List<GeneralPayoutSummary> byPolicyNumber(String policyNumber) {
        Policy policy = loadPolicyPort.findByPolicyNumber(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        return loadPayoutPort.findByPolicyId(policy.getPolicyId()).stream()
                .map(GeneralPayoutSummary::from)
                .toList();
    }
}
