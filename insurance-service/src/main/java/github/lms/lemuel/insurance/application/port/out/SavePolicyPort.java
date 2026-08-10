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

    /**
     * 계약 발행 INSERT — 청약 승인 경로 전용.
     *
     * <p>도메인 {@link Policy} 가 들고 있지 않는 SoR 컬럼은 {@code attributes} 로 함께 전달한다.
     *
     * @return 저장 후 재구성된 도메인 (id 채번 반영)
     */
    Policy insertIssued(Policy policy, PolicyIssuanceAttributes attributes);

    /**
     * @param applicationId      발행 근거 청약 (insurance_policies.application_id)
     * @param productCode        상품 코드
     * @param coverageAmount     보장금액 (청약 승인 조건)
     * @param paymentCycleMonths 납입 주기 (개월)
     */
    record PolicyIssuanceAttributes(String applicationId, String productCode,
                                    java.math.BigDecimal coverageAmount, int paymentCycleMonths) {
    }
}
