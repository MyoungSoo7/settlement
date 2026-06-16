package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderStatus;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분할결제 환불 오케스트레이터 단위 테스트 — tender 별 독립 트랜잭션 동작 검증.
 *
 * <p>주 관심사: 한 tender PG 환불이 실패해도 <b>앞서 성공한 tender 환불은 보존</b>되고(부분 성공
 * 내구성), 종료 이벤트는 전부 성공한 경우에만 발행된다. (실제 트랜잭션 격리는 통합 테스트 영역이며,
 * 여기서는 in-memory fake 로 오케스트레이션 순서/상태/이벤트만 격리 검증한다.)
 */
class RefundSplitPaymentServiceTest {

    private FakePaymentStore store;
    private RecordingPgClient pg;
    private CountingEventPublisher events;
    private RefundSplitPaymentService service;

    @BeforeEach
    void setUp() {
        store = new FakePaymentStore();
        pg = new RecordingPgClient();
        events = new CountingEventPublisher();
        TenderRefundExecutor executor = new TenderRefundExecutor(
                store, store, pg, (orderId, status) -> store.lastOrderStatus = status, events);
        service = new RefundSplitPaymentService(store, executor);
    }

    @Test
    void 전액_환불_성공시_모든_tender_REFUNDED_주문_REFUNDED_이벤트_1회() {
        store.put(splitPayment());

        PaymentDomain result = service.refundSplit(1L, new BigDecimal("30000"));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(result.getRefundedAmount()).isEqualByComparingTo("30000");
        assertThat(store.lastOrderStatus).isEqualTo("REFUNDED");
        assertThat(events.count).isEqualTo(1);
        // 역순: seq2(20000) 먼저, seq1(10000) 나중
        assertThat(pg.refundedAmounts).containsExactly(new BigDecimal("20000"), new BigDecimal("10000"));
    }

    @Test
    void 두번째_tender_PG_실패시_첫_tender_환불은_보존되고_이벤트_미발행() {
        store.put(splitPayment());
        pg.failOnCall(2); // seq1 환불 차례(2번째 PG 호출)에서 실패

        assertThatThrownBy(() -> service.refundSplit(1L, new BigDecimal("30000")))
                .isInstanceOf(RuntimeException.class);

        PaymentDomain after = store.load();
        // 1번째로 처리된 seq2(20000)는 커밋 보존, 2번째 seq1 은 미적용
        PaymentTender seq2 = tender(after, 2);
        PaymentTender seq1 = tender(after, 1);
        assertThat(seq2.getRefundedAmount()).isEqualByComparingTo("20000");
        assertThat(seq2.getStatus()).isEqualTo(TenderStatus.REFUNDED);
        assertThat(seq1.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(seq1.getStatus()).isEqualTo(TenderStatus.CAPTURED);
        // 결제는 부분 환불 상태로 CAPTURED 유지, 종료 이벤트 미발행
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("20000");
        assertThat(events.count).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** 30,000원 = CARD(seq1, 10,000) + CARD(seq2, 20,000), 둘 다 외부 PG + CAPTURED. */
    private static PaymentDomain splitPayment() {
        LocalDateTime now = LocalDateTime.now();
        PaymentTender t1 = PaymentTender.rehydrate(101L, 1L, TenderType.CARD,
                new BigDecimal("10000"), BigDecimal.ZERO, "PG-1", TenderStatus.CAPTURED, 1, now, now);
        PaymentTender t2 = PaymentTender.rehydrate(102L, 1L, TenderType.CARD,
                new BigDecimal("20000"), BigDecimal.ZERO, "PG-2", TenderStatus.CAPTURED, 2, now, now);
        PaymentDomain p = new PaymentDomain(1L, 9L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "SPLIT", null, now, now, now);
        p.replaceTenders(List.of(t1, t2));
        return p;
    }

    private static PaymentTender tender(PaymentDomain p, int sequence) {
        return p.getTenders().stream().filter(t -> t.getSequence() == sequence)
                .findFirst().orElseThrow();
    }

    private static class FakePaymentStore implements LoadPaymentPort, SavePaymentPort {
        private PaymentDomain stored;
        private String lastOrderStatus;

        void put(PaymentDomain p) { this.stored = p; }
        PaymentDomain load() { return stored; }

        @Override public Optional<PaymentDomain> loadById(Long id) { return Optional.ofNullable(stored); }
        @Override public Optional<PaymentDomain> loadByIdForUpdate(Long id) { return Optional.ofNullable(stored); }
        @Override public Optional<PaymentDomain> loadByOrderId(Long orderId) { return Optional.ofNullable(stored); }
        @Override public PaymentDomain save(PaymentDomain p) { this.stored = p; return p; }
    }

    private static class RecordingPgClient implements PgClientPort {
        private final java.util.List<BigDecimal> refundedAmounts = new java.util.ArrayList<>();
        private int failAtCall = -1;
        private int calls = 0;

        void failOnCall(int n) { this.failAtCall = n; }

        @Override public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) { return "PG-X"; }
        @Override public void capture(String pgTransactionId, BigDecimal amount) { }
        @Override public void refund(String pgTransactionId, BigDecimal amount) {
            calls++;
            if (calls == failAtCall) {
                throw new IllegalStateException("PG refund 실패 (call=" + calls + ")");
            }
            refundedAmounts.add(amount);
        }
    }

    private static class CountingEventPublisher implements PublishEventPort {
        private int count = 0;
        @Override public void publishPaymentCreated(Long paymentId, Long orderId) { }
        @Override public void publishPaymentAuthorized(Long paymentId) { }
        @Override public void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount,
                java.time.LocalDateTime capturedAt, String paymentMethod, String pgTransactionId,
                github.lms.lemuel.payment.application.port.out.SellerSettlementMeta sellerMeta) { }
        @Override public void publishPaymentRefunded(Long paymentId, Long orderId, BigDecimal refundedAmount) { count++; }
    }
}
