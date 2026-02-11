package github.lms.lemuel.event;

import java.util.List;

/**
 * 정산 데이터 인덱싱 이벤트
 * 배치 작업 완료 후 Elasticsearch 동기화를 위해 발행
 */
public class SettlementIndexEvent {

    private final List<Long> settlementIds;
    private final IndexEventType eventType;

    public enum IndexEventType {
        BATCH_CREATED,      // 배치로 정산 생성됨
        BATCH_CONFIRMED,    // 배치로 정산 확정됨
        SINGLE_UPDATED,     // 개별 정산 업데이트
        REFUND_PROCESSED,   // 환불 처리됨
        APPROVED,           // 정산 승인됨
        REJECTED            // 정산 반려됨
    }

    public SettlementIndexEvent(List<Long> settlementIds, IndexEventType eventType) {
        this.settlementIds = settlementIds;
        this.eventType = eventType;
    }

    public List<Long> getSettlementIds() {
        return settlementIds;
    }

    public IndexEventType getEventType() {
        return eventType;
    }
}
