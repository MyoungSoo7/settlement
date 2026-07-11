package github.lms.lemuel.settlement.adapter.out.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SettlementSearchDocument POJO 단위 테스트 — 모든 getter/setter 및
 * setSettlementId 의 id 동기화 부수효과를 검증한다.
 */
class SettlementSearchDocumentTest {

    @Test
    @DisplayName("기본 생성자는 indexedAt 을 자동 설정한다")
    void constructor_setsIndexedAt() {
        SettlementSearchDocument doc = new SettlementSearchDocument();

        assertThat(doc.getIndexedAt()).isNotNull();
    }

    @Test
    @DisplayName("setSettlementId 는 id 필드도 문자열로 동기화한다")
    void setSettlementId_alsoSetsId() {
        SettlementSearchDocument doc = new SettlementSearchDocument();

        doc.setSettlementId(42L);

        assertThat(doc.getSettlementId()).isEqualTo(42L);
        assertThat(doc.getId()).isEqualTo("42");
    }

    @Test
    @DisplayName("setSettlementId(null) 은 id 도 null 로 동기화한다")
    void setSettlementId_null_setsIdNull() {
        SettlementSearchDocument doc = new SettlementSearchDocument();

        doc.setSettlementId(null);

        assertThat(doc.getSettlementId()).isNull();
        assertThat(doc.getId()).isNull();
    }

    @Test
    @DisplayName("모든 getter/setter 왕복 검증")
    void allGettersAndSetters_roundTrip() {
        SettlementSearchDocument doc = new SettlementSearchDocument();

        doc.setId("1");
        doc.setSettlementStatus("DONE");
        doc.setSettlementAmount(new BigDecimal("97000"));
        doc.setSettlementDate(LocalDate.of(2026, 4, 1));
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 4, 2, 9, 0);
        doc.setSettlementConfirmedAt(confirmedAt);
        doc.setApprovedBy(10L);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 4, 2, 10, 0);
        doc.setApprovedAt(approvedAt);
        doc.setRejectedBy(11L);
        LocalDateTime rejectedAt = LocalDateTime.of(2026, 4, 2, 11, 0);
        doc.setRejectedAt(rejectedAt);
        doc.setRejectionReason("사유 없음");
        doc.setOrderId(202L);
        doc.setUserId(303L);
        doc.setOrderStatus("PAID");
        doc.setOrderAmount(new BigDecimal("100000"));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 3, 30, 8, 0);
        doc.setOrderCreatedAt(orderCreatedAt);
        doc.setPaymentId(404L);
        doc.setPaymentStatus("CAPTURED");
        doc.setPaymentAmount(new BigDecimal("100000"));
        doc.setRefundedAmount(new BigDecimal("3000"));
        doc.setPaymentMethod("CARD");
        doc.setPgTransactionId("pg-tx-1");
        LocalDateTime capturedAt = LocalDateTime.of(2026, 3, 30, 8, 5);
        doc.setPaymentCapturedAt(capturedAt);
        doc.setHasRefund(true);
        doc.setRefundCount(1);
        doc.setLatestRefundStatus("COMPLETED");
        doc.setRefundReason("단순 변심");
        LocalDateTime refundRequestedAt = LocalDateTime.of(2026, 4, 1, 12, 0);
        doc.setLatestRefundRequestedAt(refundRequestedAt);
        LocalDateTime refundCompletedAt = LocalDateTime.of(2026, 4, 1, 13, 0);
        doc.setLatestRefundCompletedAt(refundCompletedAt);
        LocalDateTime indexedAt = LocalDateTime.of(2026, 4, 3, 0, 0);
        doc.setIndexedAt(indexedAt);

        assertThat(doc.getId()).isEqualTo("1");
        assertThat(doc.getSettlementStatus()).isEqualTo("DONE");
        assertThat(doc.getSettlementAmount()).isEqualByComparingTo("97000");
        assertThat(doc.getSettlementDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(doc.getSettlementConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(doc.getApprovedBy()).isEqualTo(10L);
        assertThat(doc.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(doc.getRejectedBy()).isEqualTo(11L);
        assertThat(doc.getRejectedAt()).isEqualTo(rejectedAt);
        assertThat(doc.getRejectionReason()).isEqualTo("사유 없음");
        assertThat(doc.getOrderId()).isEqualTo(202L);
        assertThat(doc.getUserId()).isEqualTo(303L);
        assertThat(doc.getOrderStatus()).isEqualTo("PAID");
        assertThat(doc.getOrderAmount()).isEqualByComparingTo("100000");
        assertThat(doc.getOrderCreatedAt()).isEqualTo(orderCreatedAt);
        assertThat(doc.getPaymentId()).isEqualTo(404L);
        assertThat(doc.getPaymentStatus()).isEqualTo("CAPTURED");
        assertThat(doc.getPaymentAmount()).isEqualByComparingTo("100000");
        assertThat(doc.getRefundedAmount()).isEqualByComparingTo("3000");
        assertThat(doc.getPaymentMethod()).isEqualTo("CARD");
        assertThat(doc.getPgTransactionId()).isEqualTo("pg-tx-1");
        assertThat(doc.getPaymentCapturedAt()).isEqualTo(capturedAt);
        assertThat(doc.getHasRefund()).isTrue();
        assertThat(doc.getRefundCount()).isEqualTo(1);
        assertThat(doc.getLatestRefundStatus()).isEqualTo("COMPLETED");
        assertThat(doc.getRefundReason()).isEqualTo("단순 변심");
        assertThat(doc.getLatestRefundRequestedAt()).isEqualTo(refundRequestedAt);
        assertThat(doc.getLatestRefundCompletedAt()).isEqualTo(refundCompletedAt);
        assertThat(doc.getIndexedAt()).isEqualTo(indexedAt);
    }
}
