package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementAdjustmentStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdjustSettlementForRefundServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    AdjustSettlementForRefundService service;

    @BeforeEach
    void setUp() {
        // 실제 TimeConfig 빈과 동일한 KST 시계를 주입한다 — 조정/역분개 기준일을 KST 로 고정.
        service = new AdjustSettlementForRefundService(
                loadSettlementPort, saveSettlementPort, saveSettlementAdjustmentPort, enqueueLedgerTaskPort,
                loadSellerIdPort, publishSettlementDomainEventPort,
                Clock.system(ZoneId.of("Asia/Seoul")));
    }

    private Settlement settlement() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        s.assignId(100L);
        return s;
    }

    @Test @DisplayName("환불 반영 + 역정산 레코드 생성, refundId 전달")
    void adjusts_and_writes_adjustment() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = service.adjustSettlementForRefund(1L, new BigDecimal("20000"), 777L);

        assertThat(result.getRefundedAmount()).isEqualTo(new BigDecimal("20000"));
        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        SettlementAdjustment adj = captor.getValue();
        assertThat(adj.getSettlementId()).isEqualTo(100L);
        assertThat(adj.getRefundId()).isEqualTo(777L);
        assertThat(adj.getAmount()).isEqualTo(new BigDecimal("-20000"));
    }

    @Test @DisplayName("정산이 존재하지 않으면 SettlementNotFoundException")
    void throwsWhenSettlementMissing() {
        when(loadSettlementPort.findByPaymentIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustSettlementForRefund(
                999L, new BigDecimal("10000"), 1L))
                .isInstanceOf(SettlementNotFoundException.class);
    }

    @Test @DisplayName("레거시 2-arg 오버로드도 default 로 작동 (refundId=null)")
    void legacyOverload() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        assertThat(captor.getValue().getRefundId()).isNull();
    }

    @Test @DisplayName("3-arg 호출은 원장 역분개 작업을 정확한 인자로 아웃박스에 적재")
    void enqueues_ledger_reverse_when_refundId_present() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("12000"), 555L);

        verify(enqueueLedgerTaskPort).enqueueReverse(
                eq(100L), eq(555L), eq(new BigDecimal("12000")), eq(LocalDate.now()));
    }

    @Test @DisplayName("2-arg 레거시 호출은 원장 역분개 작업을 적재하지 않음 (refundId 없음)")
    void does_not_enqueue_for_legacy_2arg_call() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        verify(enqueueLedgerTaskPort, never()).enqueueReverse(anyLong(), anyLong(), any(), any());
    }

    @Test @DisplayName("셀러 해석되면 holdback 흡수분·즉시분 잔여를 account 이벤트로 발행")
    void publishes_consumed_and_adjusted_when_seller_present() {
        // net = 50000 - 3%(1500) = 48500, holdback 30% = 14550. refund 20000 > holdback.
        Settlement s = settlement();
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.now().plusDays(30));
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // save 는 PK 를 부여해 반환한다(발행이 adjustment.getId() 를 쓰므로 non-null 필요).
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> {
            SettlementAdjustment a = inv.getArgument(0);
            return SettlementAdjustment.rehydrate(900L, a.getSettlementId(), a.getRefundId(),
                    null, null, a.getAmount(), SettlementAdjustmentStatus.PENDING,
                    a.getAdjustmentDate(), a.getCreatedAt());
        });
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(55L));

        service.adjustSettlementForRefund(1L, new BigDecimal("20000"), 777L);

        // 흡수 14550(전액 holdback) → 유보 소진, 잔여 5450 → 즉시분(SELLER_PAYABLE) 조정.
        verify(publishSettlementDomainEventPort).publishHoldbackConsumed(
                eq(900L), eq(100L), eq(55L), eq(new BigDecimal("14550.00")));
        verify(publishSettlementDomainEventPort).publishSettlementAdjusted(
                eq(900L), eq(100L), eq(55L), eq(new BigDecimal("5450.00")), eq("SELLER_PAYABLE"));
        verify(publishSettlementDomainEventPort, never()).publishSettlementCanceled(
                anyLong(), anyLong(), any(), any());
    }

    @Test @DisplayName("HIGH-A(GL 재감사): 전액환불로 net<=0(CANCELED) 시 SELLER_PAYABLE 조정은 priorImmediate 상한을 넘지 않는다")
    void caps_payableDelta_at_priorImmediate_on_full_refund_exceeding_net() {
        // 재현 시나리오: P=10000, 수수료 3%=300, net=9700, holdback 30%=2910, I=net-holdback=6790.
        // 전액환불(refund=10000, gross) → holdback 전액(2910) 흡수 후 잔여(7090)가 즉시분 인식액(6790)을
        // 넘는다. 상한 없이 발행하면 SELLER_PAYABLE=6790-7090=-300(수수료만큼 음수 잔존) — 이 테스트는
        // 수정 전엔 payableDelta=7090 으로 실패하고, 수정 후엔 6790(=priorImmediate)으로 캡핑돼 통과한다.
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());
        s.assignId(200L);
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.now().plusDays(30));
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> {
            SettlementAdjustment a = inv.getArgument(0);
            return SettlementAdjustment.rehydrate(901L, a.getSettlementId(), a.getRefundId(),
                    null, null, a.getAmount(), SettlementAdjustmentStatus.PENDING,
                    a.getAdjustmentDate(), a.getCreatedAt());
        });
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(77L));

        Settlement result = service.adjustSettlementForRefund(1L, new BigDecimal("10000"), 888L);

        assertThat(result.getStatus()).isEqualTo(github.lms.lemuel.settlement.domain.SettlementStatus.CANCELED);
        verify(publishSettlementDomainEventPort).publishHoldbackConsumed(
                eq(901L), eq(200L), eq(77L), eq(new BigDecimal("2910.00")));
        // 상한 캡핑 — priorImmediate(6790.00) 을 넘지 않는다 (버그 시엔 7090.00 이 발행됨).
        verify(publishSettlementDomainEventPort).publishSettlementAdjusted(
                eq(901L), eq(200L), eq(77L), eq(new BigDecimal("6790.00")), eq("SELLER_PAYABLE"));
        // CANCELED 분기 — 음수 잔여를 그대로 싣지 않고 0 으로 클램프한다(계약 minimum 0 위반 방지).
        // BigDecimal.max(ZERO) 는 스케일 0 의 리터럴 ZERO 를 반환할 수 있어(예: -300.00.max(ZERO)→"0"),
        // eq() 의 scale-sensitive equals() 대신 수치 비교(isEqualByComparingTo)로 검증한다.
        ArgumentCaptor<BigDecimal> immediateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> holdbackCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishSettlementDomainEventPort).publishSettlementCanceled(
                eq(200L), eq(77L), immediateCaptor.capture(), holdbackCaptor.capture());
        assertThat(immediateCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(holdbackCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test @DisplayName("셀러 미해석이면 account 이벤트를 발행하지 않는다")
    void skips_publish_when_seller_unresolved() {
        Settlement s = settlement();
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.now().plusDays(30));
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.empty());

        service.adjustSettlementForRefund(1L, new BigDecimal("20000"), 777L);

        verify(publishSettlementDomainEventPort, never())
                .publishHoldbackConsumed(anyLong(), any(), anyLong(), any());
        verify(publishSettlementDomainEventPort, never())
                .publishSettlementAdjusted(anyLong(), any(), anyLong(), any(), any());
    }
}
