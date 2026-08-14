package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 해지·철회 유스케이스 구현 (§14) — 일반지급의 REST 진입점이 낳는 돈 경로.
 *
 * <p><b>원자성</b>: 전이(도메인 메서드) + 저장 + policy_status_changed 발행 +
 * payout 생성({@link GeneralPayoutRecorder}) + 감사가 전부 한 tx — 어느 단계가 실패해도
 * 전체 롤백, 해지만 되고 환급금이 없는 반쪽 해지는 존재하지 않는다.
 *
 * <p>해지·철회로 종료된 계약의 수수료 환수는 여기서 하지 않는다 —
 * 익일 환수 스윕({@code CommissionClawbackSweepService})이 terminal 계약을 스캔한다.
 */
@Service
@Transactional
public class PolicyTerminationService implements SurrenderPolicyUseCase, CancelPolicyUseCase {

    private final LoadPolicyPort loadPolicyPort;
    private final SavePolicyPort savePolicyPort;
    private final PublishInsuranceEventPort publishPort;
    private final GeneralPayoutRecorder payoutRecorder;
    private final Clock clock;
    private final AuditLogger auditLogger;

    public PolicyTerminationService(
            LoadPolicyPort loadPolicyPort,
            SavePolicyPort savePolicyPort,
            PublishInsuranceEventPort publishPort,
            GeneralPayoutRecorder payoutRecorder,
            Clock clock,
            AuditLogger auditLogger) {
        this.loadPolicyPort = loadPolicyPort;
        this.savePolicyPort = savePolicyPort;
        this.publishPort = publishPort;
        this.payoutRecorder = payoutRecorder;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    @Override
    public PolicyTerminationResult surrender(SurrenderPolicyCommand command) {
        LocalDate today = LocalDate.now(clock);
        Policy policy = ownedPolicy(command.policyNumber(), command.fcId());

        PolicyStatus previous = policy.getStatus();
        policy.surrender();  // D7 전이 4 — 도메인이 강제 (ACTIVE 아니면 409)

        return finishTermination(policy, previous, today, AuditAction.INSURANCE_POLICY_SURRENDERED);
    }

    @Override
    public PolicyTerminationResult cancel(CancelPolicyCommand command) {
        LocalDate today = LocalDate.now(clock);
        Policy policy = ownedPolicy(command.policyNumber(), command.fcId());

        PolicyStatus previous = policy.getStatus();
        policy.cancel(today);  // D7 전이 6·7 — 15일 창구는 도메인이 강제

        return finishTermination(policy, previous, today, AuditAction.INSURANCE_POLICY_CANCELLED);
    }

    /** 조회 + 담당 FC 대조 — 전이 이전의 404/403 가드. */
    private Policy ownedPolicy(String policyNumber, String fcId) {
        Policy policy = loadPolicyPort.findByPolicyNumber(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        if (!policy.getFcId().equals(fcId)) {
            throw new PolicyOwnershipException(policyNumber, fcId);
        }
        return policy;
    }

    /** 전이 이후 공통 경로 — 저장 + 상태변경 이벤트 + payout 생성 + 건 단위 감사 (한 tx). */
    private PolicyTerminationResult finishTermination(
            Policy policy, PolicyStatus previous, LocalDate today, AuditAction auditAction) {
        savePolicyPort.save(policy);
        publishPort.publishPolicyStatusChanged(policy, previous);

        Optional<GeneralPayout> payout = payoutRecorder.recordFor(policy, previous, today);

        auditLogger.record(auditAction, "Policy", policy.getPolicyNumber(),
                auditDetail(today, policy, payout));

        return new PolicyTerminationResult(
                policy.getPolicyNumber(), policy.getStatus(),
                payout.map(GeneralPayoutSummary::from).orElse(null));
    }

    private String auditDetail(LocalDate today, Policy policy, Optional<GeneralPayout> payout) {
        return String.format(
                "{\"date\":\"%s\",\"status\":\"%s\",\"payoutType\":%s,\"payoutAmount\":\"%s\"}",
                today, policy.getStatus(),
                payout.map(p -> "\"" + p.getPayoutType().name() + "\"").orElse("null"),
                payout.map(p -> p.getAmount().toPlainString()).orElse("0"));
    }
}
