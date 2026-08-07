package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 만기·실효소멸 판정 배치 — D7 전이 5(ACTIVE→EXPIRED)·전이 3(LAPSED→EXPIRED)을 자동 집행한다.
 *
 * <p>전이는 반드시 도메인 메서드({@code expire}/{@code expireAfterLapse})를 통과한다 —
 * 배치가 상태를 직접 쓰지 않으므로, 스캔 술어와 도메인 가드가 어긋나면 조용히 넘어가는 대신
 * {@code InvalidPolicyTransitionException} 으로 드러난다.
 *
 * <p>상태 변경 1건당 {@code lemuel.insurance.policy_status_changed} 를 같은 tx 의 Outbox 에
 * 기록한다(커밋-발행 원자성).
 */
@Service
@Transactional
public class PolicyExpiryService implements ExpirePoliciesUseCase {

    private static final Logger log = LoggerFactory.getLogger(PolicyExpiryService.class);

    private final LoadPolicyPort loadPolicyPort;
    private final SavePolicyPort savePolicyPort;
    private final PublishInsuranceEventPort publishPort;

    public PolicyExpiryService(
            LoadPolicyPort loadPolicyPort,
            SavePolicyPort savePolicyPort,
            PublishInsuranceEventPort publishPort) {
        this.loadPolicyPort = loadPolicyPort;
        this.savePolicyPort = savePolicyPort;
        this.publishPort = publishPort;
    }

    @Override
    public ExpiryBatchResult expireOn(LocalDate today) {
        int matured = 0;
        for (Policy policy : loadPolicyPort.findActiveMaturedOnOrBefore(today)) {
            PolicyStatus previous = policy.getStatus();
            policy.expire();
            savePolicyPort.save(policy);
            publishPort.publishPolicyStatusChanged(policy, previous);
            matured++;
        }

        // 부활 창구(=REINSTATEMENT_WINDOW_MONTHS) 도과 스캔.
        // 술어(lapsedAt <= today - W개월)와 도메인 가드(MONTHS.between >= W)는 같은 달력 산술 —
        // 최종 판정은 expireAfterLapse 가 다시 강제한다.
        int lapsedExpired = 0;
        LocalDate lapsedCutoff = today.minusMonths(Policy.REINSTATEMENT_WINDOW_MONTHS);
        for (Policy policy : loadPolicyPort.findLapsedOnOrBefore(lapsedCutoff)) {
            PolicyStatus previous = policy.getStatus();
            policy.expireAfterLapse(today);
            savePolicyPort.save(policy);
            publishPort.publishPolicyStatusChanged(policy, previous);
            lapsedExpired++;
        }

        if (matured + lapsedExpired > 0) {
            log.info("[PolicyExpiry] today={} 만기소멸={} 실효소멸={}", today, matured, lapsedExpired);
        }
        return new ExpiryBatchResult(matured, lapsedExpired);
    }
}
