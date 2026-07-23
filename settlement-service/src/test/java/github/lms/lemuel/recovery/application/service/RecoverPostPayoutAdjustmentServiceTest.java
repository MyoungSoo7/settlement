package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.PublishSellerRecoveryEventPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시드 P0-6 채권 발생 — "송금 완료 정산의 회수분 중 holdback 미흡수 잔여만 채권" 규약.
 */
@ExtendWith(MockitoExtension.class)
class RecoverPostPayoutAdjustmentServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 20);
    private static final BigDecimal RECOVERED = new BigDecimal("3000.00");

    @Mock LoadSellerRecoveryPort loadRecoveryPort;
    @Mock SaveSellerRecoveryPort saveRecoveryPort;
    @Mock LoadPayoutPort loadPayoutPort;
    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock RecoveryEntryUseCase recoveryEntryUseCase;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    @Mock PublishSellerRecoveryEventPort publishSellerRecoveryEventPort;

    private RecoverPostPayoutAdjustmentService service;

    @BeforeEach
    void setUp() {
        service = new RecoverPostPayoutAdjustmentService(loadRecoveryPort, saveRecoveryPort,
                loadPayoutPort, loadSettlementPort, saveSettlementPort, loadSellerIdPort,
                recoveryEntryUseCase, publishSettlementDomainEventPort, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("이미 같은 조정으로 채권이 열려 있으면 아무것도 하지 않는다 (멱등)")
    void skipsWhenAlreadyRecovered() {
        when(loadRecoveryPort.findBySourceAdjustmentId(11L))
                .thenReturn(Optional.of(SellerRecovery.open(11L, 7L, RECOVERED)));

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isEmpty();
        verifyNoInteractions(saveRecoveryPort, saveSettlementPort, recoveryEntryUseCase,
                publishSettlementDomainEventPort, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("즉시지급 Payout 이 COMPLETED 가 아니면 대상 아님 — 기존 회수 경로 몫")
    void skipsWhenPayoutNotCompleted() {
        when(loadRecoveryPort.findBySourceAdjustmentId(11L)).thenReturn(Optional.empty());
        Payout requested = mock(Payout.class);
        when(requested.getStatus()).thenReturn(PayoutStatus.REQUESTED);
        when(loadPayoutPort.findBySettlementIdAndType(501L, PayoutType.IMMEDIATE))
                .thenReturn(Optional.of(requested));

        assertThat(service.recordIfPostPayout(501L, 11L, RECOVERED, DATE)).isEmpty();

        when(loadPayoutPort.findBySettlementIdAndType(502L, PayoutType.IMMEDIATE))
                .thenReturn(Optional.empty());
        when(loadRecoveryPort.findBySourceAdjustmentId(12L)).thenReturn(Optional.empty());
        assertThat(service.recordIfPostPayout(502L, 12L, RECOVERED, DATE)).isEmpty();

        verifyNoInteractions(saveRecoveryPort, saveSettlementPort, recoveryEntryUseCase,
                publishSettlementDomainEventPort, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("셀러 미해석이면 흡수·채권 없이 종료 (조정 레코드가 수기 대응 근거)")
    void skipsWhenSellerUnresolved() {
        stubCompletedPayout(501L);
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.empty());

        assertThat(service.recordIfPostPayout(501L, 11L, RECOVERED, DATE)).isEmpty();

        verify(settlement, never()).consumeHoldbackForRefund(any());
        verifyNoInteractions(saveRecoveryPort, saveSettlementPort, recoveryEntryUseCase,
                publishSettlementDomainEventPort, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("holdback 이 전액 흡수하면 채권 없이 정산만 저장한다")
    void absorbsFullyIntoHoldback() {
        stubCompletedPayout(501L);
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(RECOVERED);

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isEmpty();
        verify(saveSettlementPort).save(settlement);
        // 전액 흡수 → 유보 소진(현금유출) 이벤트만, 채권(Opened) 은 없음.
        verify(publishSettlementDomainEventPort).publishHoldbackConsumed(11L, 501L, 7L, RECOVERED);
        verifyNoInteractions(saveRecoveryPort, recoveryEntryUseCase, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("부분 흡수 — 잔여만 채권 원금이 되고 발생 분개가 1건 적재된다")
    void opensRecoveryForUnabsorbedRemainder() {
        stubCompletedPayout(501L);
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(new BigDecimal("1000.00"));
        when(saveRecoveryPort.save(any())).thenAnswer(inv -> {
            SellerRecovery r = inv.getArgument(0);
            return SellerRecovery.rehydrate(99L, r.getSourceAdjustmentId(), r.getSellerId(),
                    r.getOriginalAmount(), r.getAllocatedAmount(), r.getStatus(),
                    r.getCreatedAt(), r.getClosedAt());
        });

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().getOriginalAmount()).isEqualByComparingTo("2000.00");
        assertThat(result.get().getSellerId()).isEqualTo(7L);
        assertThat(result.get().getStatus()).isEqualTo(RecoveryStatus.OPEN);
        verify(saveSettlementPort).save(settlement);
        verify(recoveryEntryUseCase).recognizeReceivable(99L, 501L, new BigDecimal("2000.00"), DATE);
        // 부분 흡수 1000 → 유보 소진 이벤트, 잔여 2000 → 채권 발생(Opened) 이벤트.
        verify(publishSettlementDomainEventPort)
                .publishHoldbackConsumed(11L, 501L, 7L, new BigDecimal("1000.00"));
        verify(publishSellerRecoveryEventPort)
                .publishRecoveryOpened(99L, 7L, new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("holdback 이 없으면(흡수 0) 회수 전액이 채권이 되고 정산 저장은 생략한다")
    void opensFullRecoveryWhenNoHoldback() {
        stubCompletedPayout(501L);
        Settlement settlement = stubSettlement(501L, 100L);
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.of(7L));
        when(settlement.consumeHoldbackForRefund(RECOVERED)).thenReturn(BigDecimal.ZERO);
        when(saveRecoveryPort.save(any())).thenAnswer(inv -> {
            SellerRecovery r = inv.getArgument(0);
            return SellerRecovery.rehydrate(98L, r.getSourceAdjustmentId(), r.getSellerId(),
                    r.getOriginalAmount(), r.getAllocatedAmount(), r.getStatus(),
                    r.getCreatedAt(), r.getClosedAt());
        });

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().getOriginalAmount()).isEqualByComparingTo(RECOVERED);
        verify(saveSettlementPort, never()).save(any());
        verify(recoveryEntryUseCase).recognizeReceivable(any(), eq(501L), eq(RECOVERED), eq(DATE));
        // 흡수 0 → 유보 소진 이벤트 없음, 회수 전액이 채권(Opened) 이벤트로.
        verify(publishSettlementDomainEventPort, never())
                .publishHoldbackConsumed(anyLong(), any(), anyLong(), any());
        verify(publishSellerRecoveryEventPort).publishRecoveryOpened(98L, 7L, RECOVERED);
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private void stubCompletedPayout(Long settlementId) {
        when(loadRecoveryPort.findBySourceAdjustmentId(anyLong())).thenReturn(Optional.empty());
        Payout completed = mock(Payout.class);
        when(completed.getStatus()).thenReturn(PayoutStatus.COMPLETED);
        when(loadPayoutPort.findBySettlementIdAndType(settlementId, PayoutType.IMMEDIATE))
                .thenReturn(Optional.of(completed));
    }

    private Settlement stubSettlement(Long settlementId, Long paymentId) {
        Settlement settlement = mock(Settlement.class);
        when(settlement.getPaymentId()).thenReturn(paymentId);
        when(loadSettlementPort.findById(settlementId)).thenReturn(Optional.of(settlement));
        return settlement;
    }
}
