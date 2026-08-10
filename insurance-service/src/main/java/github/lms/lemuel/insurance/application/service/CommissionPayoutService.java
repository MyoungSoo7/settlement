package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 수수료 회차 지급 배치 — due_date 도래 SCHEDULED 회차를 PAID 로 전이한다 (D4).
 *
 * <p><b>계약 상태 게이트</b>: ACTIVE 계약만 지급.
 * <ul>
 *   <li>LAPSED — 부활 여지가 있어 보류. 회차는 SCHEDULED 로 남고 다음 배치가 재시도한다.</li>
 *   <li>terminal — 환수 스윕({@code CommissionClawbackSweepService})이 CANCELLED 로 소멸시킨다.</li>
 * </ul>
 *
 * <p>지급 1건당 {@code lemuel.insurance.commission_paid} 를 같은 tx 의 Outbox 에 기록한다.
 * 이중 지급은 도메인 전이 가드(SCHEDULED→PAID 만 허용)가 차단한다.
 */
@Service
@Transactional
public class CommissionPayoutService implements PayDueCommissionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(CommissionPayoutService.class);

    private final LoadCommissionSchedulePort loadSchedulePort;
    private final SaveCommissionSchedulePort saveSchedulePort;
    private final LoadPolicyPort loadPolicyPort;
    private final PublishInsuranceEventPort publishPort;

    public CommissionPayoutService(
            LoadCommissionSchedulePort loadSchedulePort,
            SaveCommissionSchedulePort saveSchedulePort,
            LoadPolicyPort loadPolicyPort,
            PublishInsuranceEventPort publishPort) {
        this.loadSchedulePort = loadSchedulePort;
        this.saveSchedulePort = saveSchedulePort;
        this.loadPolicyPort = loadPolicyPort;
        this.publishPort = publishPort;
    }

    @Override
    public PayoutBatchResult payDueOn(LocalDate today) {
        int paid = 0;
        int held = 0;
        Map<String, Optional<Policy>> policyCache = new HashMap<>();

        for (CommissionSchedule schedule : loadSchedulePort.findDueScheduledOnOrBefore(today)) {
            Optional<Policy> policy = policyCache.computeIfAbsent(
                    schedule.getPolicyId(), loadPolicyPort::findByPolicyId);

            if (policy.isEmpty() || policy.get().getStatus() != PolicyStatus.ACTIVE) {
                held++;
                continue;
            }

            schedule.markPaid(today);
            saveSchedulePort.save(schedule);
            publishPort.publishCommissionPaid(policy.get(), schedule);
            paid++;
        }

        if (paid + held > 0) {
            log.info("[CommissionPayout] today={} 지급={} 보류={}", today, paid, held);
        }
        return new PayoutBatchResult(paid, held);
    }
}
