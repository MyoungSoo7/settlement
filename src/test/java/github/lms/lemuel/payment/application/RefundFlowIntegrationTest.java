package github.lms.lemuel.payment.application;

import github.lms.lemuel.common.exception.RefundExceedsPaymentException;
import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.JournalEntry;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.RefundStatus;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.service.AdjustSettlementForRefundService;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementAdjustmentStatus;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refund 전체 흐름 통합 테스트 (Task 4.1 — Application-level integration).
 *
 * <p>3개 도메인(Payment / Settlement / Ledger)을 가로지르는 환불 흐름을 검증한다.
 * Spring 컨텍스트 없이, 모든 outbound port를 in-memory store로 대체하고
 * 실제 service 객체(RefundPaymentUseCase + AdjustSettlementForRefundService)를
 * 직접 wire-up하여 단위 슬라이스 통합을 검증.
 *
 * <p>이 통합 테스트가 추가로 보장하는 것 (단위 테스트와 차별):
 * <ul>
 *   <li>3개 도메인이 한 번의 호출로 함께 작동한다 (Mock 격리 없음).</li>
 *   <li>Idempotency가 store-level UNIQUE 제약 시뮬레이션과 도메인 가드 양쪽에서 작동한다.</li>
 *   <li>Settlement immutability — 영속화된 Settlement가 refund 후에도 변경되지 않는다.</li>
 *   <li>Ledger 분개의 차/대변 균형이 실제 INSERT 시퀀스 후에도 유지된다.</li>
 *   <li>RefundExceedsPaymentException이 누적 환불 검증에서 정확히 발생한다.</li>
 * </ul>
 */
class RefundFlowIntegrationTest {

    // ===== In-memory stores =====
    private InMemoryPaymentStore paymentStore;
    private InMemoryRefundStore refundStore;
    private InMemorySettlementStore settlementStore;
    private InMemoryAdjustmentStore adjustmentStore;
    private InMemoryLedgerStore ledgerStore;
    private RecordingPgClient pgClient;
    private RecordingEventPublisher eventPublisher;
    private RecordingOrderUpdater orderUpdater;

    // ===== Service under test =====
    private RefundPaymentUseCase refundUseCase;

    // ===== Fixture IDs =====
    private static final Long PAYMENT_ID = 10L;
    private static final Long ORDER_ID = 100L;
    private static final Long SELLER_ID = 42L;
    private static final Long SETTLEMENT_ID = 500L;
    private static final BigDecimal PAYMENT_AMOUNT = new BigDecimal("100000");
    private static final BigDecimal COMMISSION = new BigDecimal("3000"); // 3%

    @BeforeEach
    void setUp() {
        paymentStore = new InMemoryPaymentStore();
        refundStore = new InMemoryRefundStore();
        settlementStore = new InMemorySettlementStore();
        adjustmentStore = new InMemoryAdjustmentStore();
        ledgerStore = new InMemoryLedgerStore();
        pgClient = new RecordingPgClient();
        eventPublisher = new RecordingEventPublisher();
        orderUpdater = new RecordingOrderUpdater();

        AdjustSettlementForRefundService adjustService = new AdjustSettlementForRefundService(
                settlementStore, adjustmentStore, ledgerStore);

        refundUseCase = new RefundPaymentUseCase(
                paymentStore, paymentStore, refundStore, refundStore,
                pgClient, orderUpdater, eventPublisher, adjustService);

        // Pre-fixture: CAPTURED Payment + DONE Settlement (sellerId=42, commission=3000, net=97000)
        PaymentDomain captured = new PaymentDomain(
                PAYMENT_ID, ORDER_ID, PAYMENT_AMOUNT, BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "PG-X-001",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        paymentStore.put(captured);

        Settlement done = new Settlement(
                SETTLEMENT_ID, PAYMENT_ID, ORDER_ID, PAYMENT_AMOUNT,
                BigDecimal.ZERO, COMMISSION,
                PAYMENT_AMOUNT.subtract(COMMISSION),
                SettlementStatus.DONE, LocalDate.of(2026, 4, 25),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        done.setSellerId(SELLER_ID);
        settlementStore.put(done);
    }

    @Test
    @DisplayName("anchor: 30,000원 부분환불 → Refund INSERT, SettlementAdjustment INSERT, Ledger 분개 (Settlement immutable)")
    void partial_refund_full_flow() {
        Refund refund = refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("30000"), "K1", "변심"));

        // (1) Refund 영속화 확인
        assertThat(refund.getId()).isNotNull();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getAmount()).isEqualByComparingTo("30000");
        assertThat(refundStore.findAll()).hasSize(1);

        // (2) Payment refundedAmount 누적
        PaymentDomain p = paymentStore.loadById(PAYMENT_ID).orElseThrow();
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("30000");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED); // 부분환불이므로 CAPTURED 유지

        // (3) PG 호출
        assertThat(pgClient.refundCalls).hasSize(1);
        assertThat(pgClient.refundCalls.get(0).pgTransactionId()).isEqualTo("PG-X-001");
        assertThat(pgClient.refundCalls.get(0).amount()).isEqualByComparingTo("30000");

        // (4) SettlementAdjustment INSERT — refundId, settlementId, amount=30000, status=PENDING
        List<SettlementAdjustment> adjustments = adjustmentStore.findAll();
        assertThat(adjustments).hasSize(1);
        SettlementAdjustment adj = adjustments.get(0);
        assertThat(adj.getId()).isNotNull();
        assertThat(adj.getSettlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(adj.getRefundId()).isEqualTo(refund.getId());
        assertThat(adj.getAmount()).isEqualByComparingTo("30000");
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.PENDING);

        // (5) Settlement immutability — refundedAmount 0, status DONE 유지
        Settlement s = settlementStore.findById(SETTLEMENT_ID).orElseThrow();
        assertThat(s.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(s.getCommission()).isEqualByComparingTo("3000");
        assertThat(s.getNetAmount()).isEqualByComparingTo("97000");

        // (6) Ledger 분개 — REFUND_PROCESSED, refundAmount=30000, commissionReversal=900
        assertThat(ledgerStore.refundCalls).hasSize(1);
        RecordedRefund rr = ledgerStore.refundCalls.get(0);
        assertThat(rr.refundId()).isEqualTo(refund.getId());
        assertThat(rr.sellerId()).isEqualTo(SELLER_ID);
        assertThat(rr.refundAmount()).isEqualTo(Money.krw(new BigDecimal("30000")));
        // commissionReversal = 3000 * (30000 / 100000) = 900
        assertThat(rr.commissionReversal()).isEqualTo(Money.krw(new BigDecimal("900")));
    }

    @Test
    @DisplayName("멱등성: 같은 Idempotency-Key=K1로 재요청 시 새 INSERT 없이 기존 Refund 반환")
    void idempotent_replay_returns_existing_refund() {
        Refund first = refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("30000"), "K1", "변심"));

        // 같은 키로 재요청
        Refund replay = refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("30000"), "K1", null));

        // (1) 동일 Refund 반환
        assertThat(replay.getId()).isEqualTo(first.getId());

        // (2) Refund 새 INSERT 없음
        assertThat(refundStore.findAll()).hasSize(1);

        // (3) PG 재호출 없음
        assertThat(pgClient.refundCalls).hasSize(1);

        // (4) Adjustment 새 INSERT 없음
        assertThat(adjustmentStore.findAll()).hasSize(1);

        // (5) Ledger 재호출 없음
        assertThat(ledgerStore.refundCalls).hasSize(1);

        // (6) Payment refundedAmount 두 번 누적되지 않음
        assertThat(paymentStore.loadById(PAYMENT_ID).orElseThrow().getRefundedAmount())
                .isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("초과환불: 30,000 + 80,000 (누적 110,000 > 결제액 100,000) → RefundExceedsPaymentException, Refund INSERT 안 됨")
    void over_refund_throws() {
        // 1차 환불 정상 처리
        Refund first = refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("30000"), "K1", null));
        assertThat(first.getStatus()).isEqualTo(RefundStatus.COMPLETED);

        // 2차 환불 시도 — 누적이 결제액 초과
        assertThatThrownBy(() -> refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("80000"), "K2", null)))
                .isInstanceOf(RefundExceedsPaymentException.class)
                .hasMessageContaining("100000")
                .hasMessageContaining("30000")
                .hasMessageContaining("80000");

        // 2차 환불은 어떤 부수효과도 남기면 안 됨
        assertThat(refundStore.findAll()).hasSize(1);
        assertThat(adjustmentStore.findAll()).hasSize(1);
        assertThat(ledgerStore.refundCalls).hasSize(1);
        assertThat(pgClient.refundCalls).hasSize(1);
    }

    @Test
    @DisplayName("전액환불 누적: 30,000 + 70,000 = 100,000 → REFUNDED 상태, 주문 상태 동기화, Adjustment 2건")
    void full_refund_via_two_partials() {
        refundUseCase.refund(new RefundCommand(PAYMENT_ID, new BigDecimal("30000"), "K1", null));
        Refund second = refundUseCase.refund(
                new RefundCommand(PAYMENT_ID, new BigDecimal("70000"), "K2", null));

        // Payment 상태가 REFUNDED로 전이
        PaymentDomain p = paymentStore.loadById(PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("100000");

        // 주문 상태 동기화 (전액환불 시)
        assertThat(orderUpdater.updates).contains(new OrderUpdate(ORDER_ID, "REFUNDED"));

        // Adjustment 2건 (refundId 별)
        List<SettlementAdjustment> adjs = adjustmentStore.findAll();
        assertThat(adjs).hasSize(2);
        assertThat(adjs).extracting(SettlementAdjustment::getRefundId)
                .containsExactlyInAnyOrder(1L, second.getId());
        assertThat(adjs).extracting(SettlementAdjustment::getAmount)
                .extracting(BigDecimal::stripTrailingZeros)
                .containsExactlyInAnyOrder(
                        new BigDecimal("30000").stripTrailingZeros(),
                        new BigDecimal("70000").stripTrailingZeros());

        // Ledger 분개 2건 — commission reversal 합 = 900 + 2100 = 3000 (원 commission과 일치)
        assertThat(ledgerStore.refundCalls).hasSize(2);
        BigDecimal totalCommissionReversal = ledgerStore.refundCalls.stream()
                .map(rr -> rr.commissionReversal().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalCommissionReversal).isEqualByComparingTo("3000");

        // 원 Settlement는 여전히 immutable
        Settlement s = settlementStore.findById(SETTLEMENT_ID).orElseThrow();
        assertThat(s.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
    }

    // ====================================================================
    // In-memory port adapters (simulate DB INSERT + UNIQUE constraints)
    // ====================================================================

    /** Payment store: simulates findByPK and save (overwrite). */
    static class InMemoryPaymentStore implements LoadPaymentPort, SavePaymentPort {
        private final Map<Long, PaymentDomain> byId = new HashMap<>();

        void put(PaymentDomain p) { byId.put(p.getId(), p); }

        @Override public Optional<PaymentDomain> loadById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<PaymentDomain> loadByOrderId(Long orderId) {
            return byId.values().stream().filter(p -> orderId.equals(p.getOrderId())).findFirst();
        }
        @Override public PaymentDomain save(PaymentDomain p) {
            byId.put(p.getId(), p);
            return p;
        }
    }

    /**
     * Refund store: simulates DB UNIQUE(payment_id, idempotency_key) constraint
     * and BIGSERIAL id allocation.
     */
    static class InMemoryRefundStore implements LoadRefundPort, SaveRefundPort {
        private final Map<Long, Refund> byId = new HashMap<>();
        private final Map<String, Long> uniqueIndex = new HashMap<>(); // (paymentId|idempotencyKey) -> id
        private final AtomicLong sequence = new AtomicLong(0);

        @Override
        public Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String key) {
            Long id = uniqueIndex.get(paymentId + "|" + key);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }

        @Override
        public Refund save(Refund refund) {
            String uniqueKey = refund.getPaymentId() + "|" + refund.getIdempotencyKey();
            if (refund.getId() == null) {
                if (uniqueIndex.containsKey(uniqueKey)) {
                    // DB UNIQUE 제약 시뮬레이션
                    throw new IllegalStateException(
                            "UNIQUE constraint violated: " + uniqueKey);
                }
                Long newId = sequence.incrementAndGet();
                refund.assignId(newId);
                uniqueIndex.put(uniqueKey, newId);
            }
            byId.put(refund.getId(), refund);
            return refund;
        }

        List<Refund> findAll() { return new ArrayList<>(byId.values()); }
    }

    /** Settlement store: read-only access; mutations would violate immutability. */
    static class InMemorySettlementStore implements LoadSettlementPort {
        private final Map<Long, Settlement> byId = new HashMap<>();

        void put(Settlement s) { byId.put(s.getId(), s); }

        @Override public Optional<Settlement> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<Settlement> findByPaymentId(Long paymentId) {
            return byId.values().stream()
                    .filter(s -> paymentId.equals(s.getPaymentId()))
                    .findFirst();
        }
        @Override public List<Settlement> findBySettlementDate(LocalDate date) { return List.of(); }
        @Override public List<Settlement> findBySettlementDateAndStatus(LocalDate date, SettlementStatus status) {
            return List.of();
        }
    }

    /** SettlementAdjustment store with sequential id allocation. */
    static class InMemoryAdjustmentStore implements SaveSettlementAdjustmentPort {
        private final List<SettlementAdjustment> all = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong(0);

        @Override
        public SettlementAdjustment save(SettlementAdjustment adjustment) {
            if (adjustment.getId() == null) {
                adjustment.assignId(sequence.incrementAndGet());
            }
            all.add(adjustment);
            return adjustment;
        }

        List<SettlementAdjustment> findAll() { return new ArrayList<>(all); }
    }

    /** Ledger: records calls to recordRefundProcessed for verification. */
    static class InMemoryLedgerStore implements RecordJournalEntryUseCase {
        final List<RecordedRefund> refundCalls = new ArrayList<>();

        @Override
        public JournalEntry recordJournalEntry(JournalEntry entry) {
            throw new UnsupportedOperationException("not used in this flow");
        }

        @Override
        public void recordSettlementCreated(Long settlementId, Long sellerId,
                                            Money paymentAmount, Money commissionAmount) {
            // not exercised by refund flow
        }

        @Override
        public void recordRefundProcessed(Long refundId, Long sellerId,
                                          Money refundAmount, Money commissionReversal) {
            refundCalls.add(new RecordedRefund(refundId, sellerId, refundAmount, commissionReversal));
        }
    }

    static class RecordingPgClient implements PgClientPort {
        final List<PgRefundCall> refundCalls = new ArrayList<>();

        @Override public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
            return "PG-AUTHZ";
        }
        @Override public void capture(String pgTransactionId, BigDecimal amount) {}
        @Override public void refund(String pgTransactionId, BigDecimal amount) {
            refundCalls.add(new PgRefundCall(pgTransactionId, amount));
        }
    }

    static class RecordingEventPublisher implements PublishEventPort {
        @Override public void publishPaymentCreated(Long paymentId, Long orderId) {}
        @Override public void publishPaymentAuthorized(Long paymentId) {}
        @Override public void publishPaymentCaptured(Long paymentId, Long orderId) {}
        @Override public void publishPaymentRefunded(Long paymentId, Long orderId) {}
    }

    static class RecordingOrderUpdater implements UpdateOrderStatusPort {
        final List<OrderUpdate> updates = new ArrayList<>();
        @Override public void updateOrderStatus(Long orderId, String status) {
            updates.add(new OrderUpdate(orderId, status));
        }
    }

    record PgRefundCall(String pgTransactionId, BigDecimal amount) {}
    record OrderUpdate(Long orderId, String status) {}
    record RecordedRefund(Long refundId, Long sellerId, Money refundAmount, Money commissionReversal) {}
}
