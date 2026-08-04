package github.lms.lemuel.card.domain;

/**
 * 카드 승인 거절 사유 — 정확히 4가지만 존재한다.
 *
 * <p><b>추가 금지</b>(PENDING_APPROVAL·DELINQUENT 등) — 이 enum 은 이벤트 계약
 * {@code lemuel.card.authorized} 에 직렬화되므로, 값 추가는 소비자 계약 파괴다.
 * 신규 사유가 생기면 신규 토픽 버전으로만 추가한다(ADR 0022).
 */
public enum DeclineReason {
    /** 가용 한도 부족 — master한도 또는 서브한도 초과 */
    LIMIT_EXCEEDED,
    /** 카드 또는 카드계정이 정지(SUSPENDED) 상태 */
    CARD_SUSPENDED,
    /** 임직원이 조직에서 이탈(프로젝션 비활성) */
    MEMBER_INACTIVE,
    /** 가맹점/MCC 정책 위반(차단 MCC·1회·일·월 한도 초과·해외·온라인 불허) */
    MERCHANT_POLICY_VIOLATION
}
