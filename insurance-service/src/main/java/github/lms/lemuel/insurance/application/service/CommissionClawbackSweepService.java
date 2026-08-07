package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.domain.ClawbackCalculator;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionStatus;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 환수(clawback) 스윕 배치 — D6.
 *
 * <p>terminal 종료 계약의 수수료 잔재를 정리한다:
 * <ol>
 *   <li>미지급(SCHEDULED) 회차 → CANCELLED 소멸</li>
 *   <li>기지급(PAID) 회차 → {@link ClawbackCalculator} 환수액이 0 초과면 CLAWBACK_PENDING 전환
 *       + {@code lemuel.insurance.commission_clawback_triggered} 계약 단위 1건 발행</li>
 * </ol>
 *
 * <p><b>환수 창구 종료 계약</b>(경과 개월 ≥ {@code CLAWBACK_WINDOW_MONTHS})은 환수액이 0 —
 * 기지급 회차를 PAID 그대로 남긴다(확정 지급). 스윕이 매일 재스캔하지만 무해한 no-op 이다.
 *
 * <p><b>종료일 산정</b>: 실효 후 소멸(EXPIRED + lapsedAt 존재)은 실질 보장 종료일인 실효일을,
 * 그 외(해지·철회·만기소멸)는 스윕 처리일을 종료일로 본다 — 해지·철회는 별도 종료일 컬럼이
 * 없고 스윕이 일 단위로 돌아 오차가 하루를 넘지 않는다.
 */
@Service
@Transactional
public class CommissionClawbackSweepService implements SweepCommissionClawbacksUseCase {

    private static final Logger log = LoggerFactory.getLogger(CommissionClawbackSweepService.class);

    private final LoadPolicyPort loadPolicyPort;
    private final LoadCommissionSchedulePort loadSchedulePort;
    private final SaveCommissionSchedulePort saveSchedulePort;
    private final PublishInsuranceEventPort publishPort;

    public CommissionClawbackSweepService(
            LoadPolicyPort loadPolicyPort,
            LoadCommissionSchedulePort loadSchedulePort,
            SaveCommissionSchedulePort saveSchedulePort,
            PublishInsuranceEventPort publishPort) {
        this.loadPolicyPort = loadPolicyPort;
        this.loadSchedulePort = loadSchedulePort;
        this.saveSchedulePort = saveSchedulePort;
        this.publishPort = publishPort;
    }

    @Override
    public ClawbackSweepResult sweepOn(LocalDate today) {
        int cancelled = cancelUnpaidOfTerminalPolicies();
        int[] flagged = flagClawbacks(today);

        if (cancelled + flagged[1] > 0) {
            log.info("[ClawbackSweep] today={} 미지급소멸={} 환수계약={} 환수회차={}",
                    today, cancelled, flagged[0], flagged[1]);
        }
        return new ClawbackSweepResult(cancelled, flagged[0], flagged[1]);
    }

    /** terminal 계약의 SCHEDULED 회차 소멸 — 더 지급될 일 없음. */
    private int cancelUnpaidOfTerminalPolicies() {
        int cancelled = 0;
        for (Policy policy : loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED)) {
            for (CommissionSchedule schedule
                    : loadSchedulePort.findByPolicyIdAndStatus(policy.getPolicyId(), CommissionStatus.SCHEDULED)) {
                schedule.cancelRemaining();
                saveSchedulePort.save(schedule);
                cancelled++;
            }
        }
        return cancelled;
    }

    /** terminal 계약의 PAID 회차 환수 트리거. 반환: [환수 계약 수, 환수 회차 수]. */
    private int[] flagClawbacks(LocalDate today) {
        int policies = 0;
        int installments = 0;
        for (Policy policy : loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID)) {
            BigDecimal paidTotal = loadSchedulePort.sumPaidTotalByPolicyId(policy.getPolicyId());
            BigDecimal clawbackAmount = ClawbackCalculator.calculate(
                    paidTotal, policy.getEffectiveDate(), clawbackEndDate(policy, today), policy.getStatus());

            if (clawbackAmount.signum() <= 0) {
                continue;  // 환수 창구 종료 — 기지급 확정, PAID 유지
            }

            for (CommissionSchedule schedule
                    : loadSchedulePort.findByPolicyIdAndStatus(policy.getPolicyId(), CommissionStatus.PAID)) {
                schedule.flagClawbackPending();
                saveSchedulePort.save(schedule);
                installments++;
            }
            publishPort.publishCommissionClawbackTriggered(policy, paidTotal, clawbackAmount);
            policies++;
        }
        return new int[]{policies, installments};
    }

    private LocalDate clawbackEndDate(Policy policy, LocalDate today) {
        if (policy.getStatus() == PolicyStatus.EXPIRED && policy.getLapsedAt() != null) {
            return policy.getLapsedAt();  // 실효 후 소멸 — 실질 보장 종료는 실효일
        }
        return today;
    }
}
