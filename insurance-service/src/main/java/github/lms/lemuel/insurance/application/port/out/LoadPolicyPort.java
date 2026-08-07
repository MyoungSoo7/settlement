package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.CommissionStatus;
import github.lms.lemuel.insurance.domain.Policy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 계약(Policy) 조회 포트 — 배치 스캔 + 단건 조회.
 *
 * <p>배치 스캔 2개는 V1 의 부분 인덱스와 1:1 대응한다:
 * <ul>
 *   <li>{@link #findActiveMaturedOnOrBefore}: {@code idx_policy_maturity_active (WHERE status='ACTIVE')}</li>
 *   <li>{@link #findLapsedOnOrBefore}: {@code idx_policy_lapsed_at (WHERE status='LAPSED')}</li>
 * </ul>
 */
public interface LoadPolicyPort {

    /**
     * 만기 판정 배치 스캔 — ACTIVE 이면서 만기일이 {@code date} 이전(포함)인 계약.
     *
     * <p>D7 전이 5(ACTIVE → EXPIRED) 후보.
     */
    List<Policy> findActiveMaturedOnOrBefore(LocalDate date);

    /**
     * 실효 소멸 판정 배치 스캔 — LAPSED 이면서 실효일이 {@code lapsedCutoff} 이전(포함)인 계약.
     *
     * <p>호출자는 {@code lapsedCutoff = today - REINSTATEMENT_WINDOW_MONTHS} 로 계산해 넘긴다.
     * 부활 창구 도과 여부의 최종 판정은 도메인({@code Policy.expireAfterLapse})이 다시 강제한다.
     */
    List<Policy> findLapsedOnOrBefore(LocalDate lapsedCutoff);

    /**
     * 환수·정리 스윕 배치 스캔 — terminal 상태(SURRENDERED·EXPIRED·CANCELLED)로 종료된 계약 중
     * {@code commissionStatus} 상태의 수수료 회차가 남아 있는 계약.
     *
     * <p>PAID 로 조회하면 환수 대상, SCHEDULED 로 조회하면 미지급 소멸 대상이다.
     */
    List<Policy> findTerminalWithCommissionsInStatus(CommissionStatus commissionStatus);

    /** policy_id(UUID) 단건 조회 — 수수료 회차가 들고 있는 참조로 역조회. */
    Optional<Policy> findByPolicyId(String policyId);

    /** 증권번호(자연키) 단건 조회. */
    Optional<Policy> findByPolicyNumber(String policyNumber);
}
