package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.Policy;

/**
 * 계약(Policy) 저장 포트.
 *
 * <p>도메인 스냅샷의 <b>전이 가능 필드만</b>(status·lapsed_at·consecutive_premium_failures)
 * 기존 행에 반영한다 — Policy 도메인이 들고 있지 않는 SoR 컬럼(application_id·product_code·
 * coverage_amount 등)은 어댑터가 보존한다.
 */
public interface SavePolicyPort {

    /**
     * 전이 결과 반영. {@code policy.getId()} 가 가리키는 기존 행을 갱신한다.
     *
     * @return 저장 후 재구성된 도메인 (version 반영)
     */
    Policy save(Policy policy);
}
