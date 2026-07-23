package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.RecoveryAllocationPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryAllocation;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시드 P0-6 상계 — "후속 정산 확정 시 OPEN 채권을 오래된 순으로 소진" 규약.
 */
@ExtendWith(MockitoExtension.class)
class OffsetSellerRecoveryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 21);

    @Mock LoadSellerRecoveryPort loadRecoveryPort;
    @Mock SaveSellerRecoveryPort saveRecoveryPort;
    @Mock RecoveryAllocationPort allocationPort;
    @Mock RecoveryEntryUseCase recoveryEntryUseCase;

    private OffsetSellerRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new OffsetSellerRecoveryService(loadRecoveryPort, saveRecoveryPort,
                allocationPort, recoveryEntryUseCase);
    }

    @Test
    @DisplayName("즉시지급액이 0 이하이면 상계하지 않는다")
    void returnsZeroForNonPositiveImmediate() {
        assertThat(service.offsetForConfirmedSettlement(501L, 7L, BigDecimal.ZERO, DATE))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(service.offsetForConfirmedSettlement(501L, 7L, null, DATE))
                .isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(loadRecoveryPort, saveRecoveryPort, allocationPort, recoveryEntryUseCase);
    }

    @Test
    @DisplayName("재실행 멱등 — 기존 상계 총액이 있으면 새 상계 없이 그 값을 반환한다")
    void reusesPriorAllocationsOnRerun() {
        when(allocationPort.sumBySettlementId(501L)).thenReturn(new BigDecimal("800.00"));

        BigDecimal offset = service.offsetForConfirmedSettlement(
                501L, 7L, new BigDecimal("5000.00"), DATE);

        assertThat(offset).isEqualByComparingTo("800.00");
        verifyNoInteractions(loadRecoveryPort, saveRecoveryPort, recoveryEntryUseCase);
        verify(allocationPort, never()).save(any());
    }

    @Test
    @DisplayName("OPEN 채권이 없으면 0")
    void returnsZeroWithoutOpenRecoveries() {
        when(allocationPort.sumBySettlementId(501L)).thenReturn(BigDecimal.ZERO);
        when(loadRecoveryPort.findOpenBySellerIdForUpdate(7L)).thenReturn(List.of());

        assertThat(service.offsetForConfirmedSettlement(501L, 7L, new BigDecimal("5000.00"), DATE))
                .isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(saveRecoveryPort, recoveryEntryUseCase);
    }

    @Test
    @DisplayName("부분 상계 — 채권 잔액이 지급액보다 크면 지급액 전액을 상계하고 채권은 OPEN 유지")
    void partiallyOffsetsAgainstSingleRecovery() {
        when(allocationPort.sumBySettlementId(501L)).thenReturn(BigDecimal.ZERO);
        SellerRecovery recovery = openRecovery(99L, "3000.00");
        when(loadRecoveryPort.findOpenBySellerIdForUpdate(7L)).thenReturn(List.of(recovery));
        stubAllocationSave();

        BigDecimal offset = service.offsetForConfirmedSettlement(
                501L, 7L, new BigDecimal("1000.00"), DATE);

        assertThat(offset).isEqualByComparingTo("1000.00");
        assertThat(recovery.getAllocatedAmount()).isEqualByComparingTo("1000.00");
        assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.OPEN);
        verify(saveRecoveryPort).save(recovery);
        ArgumentCaptor<RecoveryAllocation> captor = ArgumentCaptor.forClass(RecoveryAllocation.class);
        verify(allocationPort).save(captor.capture());
        assertThat(captor.getValue().settlementId()).isEqualTo(501L);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("1000.00");
        verify(recoveryEntryUseCase).offsetReceivable(eq(1000L), eq(99L),
                eq(new BigDecimal("1000.00")), eq(DATE));
    }

    @Test
    @DisplayName("여러 채권은 오래된 순으로 소진한다 — 앞 채권 전액(CLOSED) 후 다음 채권")
    void consumesRecoveriesOldestFirst() {
        when(allocationPort.sumBySettlementId(501L)).thenReturn(BigDecimal.ZERO);
        SellerRecovery older = openRecovery(98L, "500.00");
        SellerRecovery newer = openRecovery(99L, "3000.00");
        when(loadRecoveryPort.findOpenBySellerIdForUpdate(7L)).thenReturn(List.of(older, newer));
        stubAllocationSave();

        BigDecimal offset = service.offsetForConfirmedSettlement(
                501L, 7L, new BigDecimal("1000.00"), DATE);

        assertThat(offset).isEqualByComparingTo("1000.00");
        assertThat(older.getStatus()).isEqualTo(RecoveryStatus.CLOSED);
        assertThat(older.getAllocatedAmount()).isEqualByComparingTo("500.00");
        assertThat(newer.getStatus()).isEqualTo(RecoveryStatus.OPEN);
        assertThat(newer.getAllocatedAmount()).isEqualByComparingTo("500.00");
        verify(recoveryEntryUseCase).offsetReceivable(any(), eq(98L), eq(new BigDecimal("500.00")), eq(DATE));
        verify(recoveryEntryUseCase).offsetReceivable(any(), eq(99L), eq(new BigDecimal("500.00")), eq(DATE));
    }

    @Test
    @DisplayName("채권 합계가 지급액보다 작으면 합계까지만 상계한다 — 잔여 지급은 호출자 몫")
    void stopsWhenRecoveriesExhausted() {
        when(allocationPort.sumBySettlementId(501L)).thenReturn(BigDecimal.ZERO);
        SellerRecovery only = openRecovery(99L, "800.00");
        when(loadRecoveryPort.findOpenBySellerIdForUpdate(7L)).thenReturn(List.of(only));
        stubAllocationSave();

        BigDecimal offset = service.offsetForConfirmedSettlement(
                501L, 7L, new BigDecimal("5000.00"), DATE);

        assertThat(offset).isEqualByComparingTo("800.00");
        assertThat(only.getStatus()).isEqualTo(RecoveryStatus.CLOSED);
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private static SellerRecovery openRecovery(Long id, String amount) {
        return SellerRecovery.rehydrate(id, id + 1000L, 7L, new BigDecimal(amount),
                BigDecimal.ZERO, RecoveryStatus.OPEN, LocalDateTime.of(2026, 7, 19, 0, 0), null);
    }

    private void stubAllocationSave() {
        when(allocationPort.save(any())).thenAnswer(inv -> {
            RecoveryAllocation a = inv.getArgument(0);
            return RecoveryAllocation.rehydrate(1000L, a.recoveryId(), a.settlementId(),
                    a.amount(), a.createdAt());
        });
    }
}
