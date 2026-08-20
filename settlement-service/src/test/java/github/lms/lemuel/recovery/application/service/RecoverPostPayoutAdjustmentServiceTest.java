package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.out.AbsorbSettlementHoldbackPort;
import github.lms.lemuel.recovery.application.port.out.AbsorbSettlementHoldbackPort.HoldbackAbsorption;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.PublishSellerRecoveryEventPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시드 P0-6 채권 발생 — "송금 완료 정산의 회수분 중 holdback 미흡수 잔여만 채권" 규약.
 *
 * <p>홀드백 소진 자체는 더 이상 이 서비스의 일이 아니다 — {@link AbsorbSettlementHoldbackPort} 뒤의
 * settlement 슬라이스가 수행하고, 그쪽 규약은 {@code ConsumeHoldbackForRecoveryServiceTest} 가 지킨다.
 * 여기서는 <b>흡수 결과를 받아 잔여를 어떻게 채권으로 만드는가</b>만 본다.
 */
@ExtendWith(MockitoExtension.class)
class RecoverPostPayoutAdjustmentServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 20);
    private static final BigDecimal RECOVERED = new BigDecimal("3000.00");

    @Mock LoadSellerRecoveryPort loadRecoveryPort;
    @Mock SaveSellerRecoveryPort saveRecoveryPort;
    @Mock LoadPayoutPort loadPayoutPort;
    @Mock AbsorbSettlementHoldbackPort absorbSettlementHoldbackPort;
    @Mock RecoveryEntryUseCase recoveryEntryUseCase;
    @Mock PublishSellerRecoveryEventPort publishSellerRecoveryEventPort;

    private RecoverPostPayoutAdjustmentService service;

    @BeforeEach
    void setUp() {
        service = new RecoverPostPayoutAdjustmentService(loadRecoveryPort, saveRecoveryPort,
                loadPayoutPort, absorbSettlementHoldbackPort, recoveryEntryUseCase,
                publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("이미 같은 조정으로 채권이 열려 있으면 아무것도 하지 않는다 (멱등)")
    void skipsWhenAlreadyRecovered() {
        when(loadRecoveryPort.findBySourceAdjustmentId(11L))
                .thenReturn(Optional.of(SellerRecovery.open(11L, 7L, RECOVERED)));

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isEmpty();
        verifyNoInteractions(saveRecoveryPort, absorbSettlementHoldbackPort, recoveryEntryUseCase,
                publishSellerRecoveryEventPort);
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

        verifyNoInteractions(saveRecoveryPort, absorbSettlementHoldbackPort, recoveryEntryUseCase,
                publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("정산이 흡수 결과를 주지 못하면(미발견·셀러 미해석) 채권 없이 종료한다")
    void skipsWhenAbsorptionUnavailable() {
        stubCompletedPayout(501L);
        when(absorbSettlementHoldbackPort.absorbForRecovery(501L, 11L, RECOVERED))
                .thenReturn(Optional.empty());

        assertThat(service.recordIfPostPayout(501L, 11L, RECOVERED, DATE)).isEmpty();

        verifyNoInteractions(saveRecoveryPort, recoveryEntryUseCase, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("holdback 이 전액 흡수하면 채권을 열지 않는다")
    void skipsRecoveryWhenFullyAbsorbed() {
        stubCompletedPayout(501L);
        when(absorbSettlementHoldbackPort.absorbForRecovery(501L, 11L, RECOVERED))
                .thenReturn(Optional.of(new HoldbackAbsorption(7L, RECOVERED)));

        assertThat(service.recordIfPostPayout(501L, 11L, RECOVERED, DATE)).isEmpty();

        verifyNoInteractions(saveRecoveryPort, recoveryEntryUseCase, publishSellerRecoveryEventPort);
    }

    @Test
    @DisplayName("부분 흡수 — 잔여만 채권 원금이 되고 발생 분개가 1건 적재된다")
    void opensRecoveryForUnabsorbedRemainder() {
        stubCompletedPayout(501L);
        when(absorbSettlementHoldbackPort.absorbForRecovery(501L, 11L, RECOVERED))
                .thenReturn(Optional.of(new HoldbackAbsorption(7L, new BigDecimal("1000.00"))));
        stubSavedRecoveryWithId(99L);

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().getOriginalAmount()).isEqualByComparingTo("2000.00");
        assertThat(result.get().getSellerId()).isEqualTo(7L);
        assertThat(result.get().getStatus()).isEqualTo(RecoveryStatus.OPEN);
        verify(recoveryEntryUseCase).recognizeReceivable(99L, 501L, new BigDecimal("2000.00"), DATE);
        verify(publishSellerRecoveryEventPort)
                .publishRecoveryOpened(99L, 7L, new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("holdback 이 없으면(흡수 0) 회수 전액이 채권이 된다")
    void opensFullRecoveryWhenNothingAbsorbed() {
        stubCompletedPayout(501L);
        when(absorbSettlementHoldbackPort.absorbForRecovery(501L, 11L, RECOVERED))
                .thenReturn(Optional.of(new HoldbackAbsorption(7L, BigDecimal.ZERO)));
        stubSavedRecoveryWithId(98L);

        Optional<SellerRecovery> result = service.recordIfPostPayout(501L, 11L, RECOVERED, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().getOriginalAmount()).isEqualByComparingTo(RECOVERED);
        verify(recoveryEntryUseCase).recognizeReceivable(any(), eq(501L), eq(RECOVERED), eq(DATE));
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

    private void stubSavedRecoveryWithId(long assignedId) {
        when(saveRecoveryPort.save(any())).thenAnswer(inv -> {
            SellerRecovery r = inv.getArgument(0);
            return SellerRecovery.rehydrate(assignedId, r.getSourceAdjustmentId(), r.getSellerId(),
                    r.getOriginalAmount(), r.getAllocatedAmount(), r.getStatus(),
                    r.getCreatedAt(), r.getClosedAt());
        });
    }
}
