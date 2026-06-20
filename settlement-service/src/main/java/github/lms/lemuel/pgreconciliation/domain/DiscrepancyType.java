package github.lms.lemuel.pgreconciliation.domain;

/**
 * PG 정산파일 vs 내부 결제 원장 차이 분류.
 *
 * <p>실무 운영에서 매월 발생하는 미세한 차이의 원인을 자동 분류해
 * 운영자가 1건씩 엑셀로 비교하던 작업을 시스템이 사전 정렬한다.
 */
public enum DiscrepancyType {
    /** 양쪽 모두 존재하지만 금액 차이가 1원 이상 — 운영자 검토 후 역정산 결정 */
    AMOUNT_MISMATCH,
    /** PG 파일에만 존재 — 내부 거래 누락 (가장 위험: 매출 누락 의심) */
    MISSING_INTERNAL,
    /** 내부 원장에만 존재 — PG 파일 누락 또는 정산 지연 */
    MISSING_PG,
    /** PG 파일 안에 동일 거래키 2 회 이상 — 이중 청구 의심 */
    DUPLICATE,
    /** 양쪽 모두 존재하고 금액 차이가 1원 미만 — 자동 보정 가능 (반올림 차이) */
    ROUNDING_DIFF;

    /**
     * 운영자 승인 없이 자동 보정 가능한 종류인가?
     * 현재는 ROUNDING_DIFF 만 자동 처리 — AMOUNT_MISMATCH 같은 큰 차이는 항상 검토 필요.
     */
    public boolean isAutoCorrectable() {
        return this == ROUNDING_DIFF;
    }
}
