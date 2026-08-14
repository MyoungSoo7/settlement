package github.lms.lemuel.insurance.domain;

/**
 * 일반지급 유형 (D-G1) — 계약자 앞 지급 3종.
 *
 * <p>일반지급은 청구 접수가 아니라 <b>Policy 상태 전이</b>가 낳는다:
 * <ul>
 *   <li>{@link #SURRENDER_REFUND}: ACTIVE→SURRENDERED(해지일 기준) 또는
 *       LAPSED→EXPIRED(실효일 기준 — 실효 이후 납입 없음)</li>
 *   <li>{@link #MATURITY_BENEFIT}: ACTIVE→EXPIRED(만기 전일까지 납입 가정)</li>
 *   <li>{@link #WITHDRAWAL_REFUND}: ACTIVE|LAPSED→CANCELLED(기납입 전액 —
 *       D6 수수료 전액 환수와 대칭)</li>
 * </ul>
 *
 * <p>V10 마이그레이션의 {@code chk_general_payout_type} CHECK 제약과 값이 1:1 로 일치한다.
 */
public enum GeneralPayoutType {

    /** 해약환급금 — 기납입보험료 × 경과월수 구간 환급률 (D-G3). */
    SURRENDER_REFUND,

    /** 만기보험금 — 기납입보험료 × 100% (V1 전 상품 만기환급형 취급). */
    MATURITY_BENEFIT,

    /** 철회환급금 — 청약철회 시 기납입보험료 전액. */
    WITHDRAWAL_REFUND
}
