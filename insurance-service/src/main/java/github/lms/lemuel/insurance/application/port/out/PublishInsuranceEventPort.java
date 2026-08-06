package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 보험 도메인 이벤트 발행 포트 — 구현은 Transactional Outbox 기록.
 *
 * <p>도메인 트랜잭션과 같은 트랜잭션에서 커밋되어 발행과 상태 변경이 원자적으로 보장된다.
 *
 * <p><b>aggregateType</b>: {@code "Insurance"} — KafkaOutboxPublisher 가 소문자로 변환 후
 * eventType 의 접두사를 제거해 토픽 이름을 조립한다.
 * <ul>
 *   <li>"Insurance" + "InsurancePolicyIssued" → {@code lemuel.insurance.policy_issued}</li>
 *   <li>"Insurance" + "InsurancePolicyStatusChanged" → {@code lemuel.insurance.policy_status_changed}</li>
 *   <li>"Insurance" + "InsuranceCommissionConfirmed" → {@code lemuel.insurance.commission_confirmed}</li>
 * </ul>
 *
 * <p><b>aggregateId</b>: 정책 식별자(policyNumber) — 한 계약의 모든 이벤트가 같은 파티션에
 * 순서대로 떨어져야 소비자(loan/investment)가 일관된 상태를 본다.
 *
 * <p><b>금액</b>: DATA-STANDARD N5 — {@link BigDecimal#toPlainString()} 문자열로 직렬화.
 * double/float 왕복 오차가 소비자마다 다른 보장금액을 보게 한다.
 */
public interface PublishInsuranceEventPort {

    /**
     * 계약 발행(청약 승인 → ACTIVE) — 토픽 {@code lemuel.insurance.policy_issued}.
     *
     * <p>리스크 연계용 {@code coverageAmount}(보장금액)를 포함한다.
     * loan/investment 는 이 이벤트를 소비해 신용보강 관점으로 활용한다.
     *
     * @param policy         발행된 계약 (policyNumber, effectiveDate, maturityDate, premiumAmount, fcId)
     * @param coverageAmount 보장금액 (신용보강 연계 소비자용)
     */
    void publishPolicyIssued(Policy policy, BigDecimal coverageAmount);

    /**
     * 계약 상태 변경 — 토픽 {@code lemuel.insurance.policy_status_changed}.
     *
     * <p>D7 허용 전이 7개가 모두 이 이벤트를 통해 외부에 알려진다.
     * 소비자는 {@code previousStatus} + {@code newStatus} 쌍을 보고 반응한다.
     *
     * @param policy         상태가 변경된 계약
     * @param previousStatus 변경 이전 상태
     */
    void publishPolicyStatusChanged(Policy policy, PolicyStatus previousStatus);

    /**
     * 수수료 확정 — 토픽 {@code lemuel.insurance.commission_confirmed}.
     *
     * <p>계약 발행 시 초년도 선지급 스케줄 12개 행이 확정된다(D4).
     * settlement-service/account-service 가 이 이벤트를 소비해 지급 처리를 시작한다.
     *
     * <p>금액({@code installmentAmount}, {@code firstYearTotal})은 반드시
     * {@link BigDecimal#toPlainString()} 으로 직렬화된다(DATA-STANDARD N5).
     *
     * @param policyNumber 대상 계약 번호 (파티션 키)
     * @param schedules    12개 회차 스케줄 목록
     */
    void publishCommissionConfirmed(String policyNumber, List<CommissionSchedule> schedules);
}
