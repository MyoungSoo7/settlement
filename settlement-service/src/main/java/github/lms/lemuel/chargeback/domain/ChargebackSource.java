package github.lms.lemuel.chargeback.domain;

/**
 * Chargeback 등록 출처.
 * 운영 분쟁 통계 + 자동/수동 분기 추적용.
 */
public enum ChargebackSource {
    /** PG 가 webhook 으로 카드사 분쟁 통지. pg_chargeback_id 멱등 키 필수. */
    PG_WEBHOOK,
    /** 운영자 수동 등록. PG 통지가 누락되거나 시연용. */
    MANUAL
}
