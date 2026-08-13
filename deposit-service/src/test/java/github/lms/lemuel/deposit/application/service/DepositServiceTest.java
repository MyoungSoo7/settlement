package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.out.*;
import github.lms.lemuel.deposit.domain.*;
import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DepositService 단위 테스트 — 포트는 전부 목이고 도메인은 실제 객체다.
 *
 * <p>핵심 검증(설계 계약):
 * <ul>
 *   <li>HOLD↔OFFSET 혼합 모델 — 상계는 선행 hold 를 요구하지 않는다.
 *       ACTIVE/PARTIALLY_CAPTURED hold 가 있으면 locked 에서 먼저 캡처하고 잔여는 release,
 *       hold 가 없거나 종료 상태면 available 에서 직접 차감한다.
 *   <li>OFFSET 엔트리의 {@code sourceHoldId} 가 null 이면 "hold 없는 늦은 청구" 감사 표식.
 *   <li>잔고 부족은 전액 롤백이 아니라 부분 상계 + 부족분 영속화 —
 *       전액 거부하면 그 재원이 payout 으로 빠져나가 막으려던 구멍이 그대로 재현된다.
 * </ul>
 */
class DepositServiceTest {

    private static final Long SELLER_ID = 42L;
    private static final Long ACCOUNT_ID = 1L;
    private static final String REF = "AUTH-1";

    private LoadDepositAccountPort loadAccountPort;
    private SaveDepositAccountPort saveAccountPort;
    private SaveDepositEntryPort saveEntryPort;
    private LoadDepositHoldPort loadHoldPort;
    private SaveDepositHoldPort saveHoldPort;
    private LoadDepositOffsetShortfallPort loadShortfallPort;
    private SaveDepositOffsetShortfallPort saveShortfallPort;
    private PublishDepositEventPort publishEventPort;

    private DepositService service;

    @BeforeEach
    void setUp() {
        loadAccountPort = mock(LoadDepositAccountPort.class);
        saveAccountPort = mock(SaveDepositAccountPort.class);
        saveEntryPort = mock(SaveDepositEntryPort.class);
        loadHoldPort = mock(LoadDepositHoldPort.class);
        saveHoldPort = mock(SaveDepositHoldPort.class);
        loadShortfallPort = mock(LoadDepositOffsetShortfallPort.class);
        saveShortfallPort = mock(SaveDepositOffsetShortfallPort.class);
        publishEventPort = mock(PublishDepositEventPort.class);

        // 영속 어댑터를 흉내낸다 — 신규 계좌에는 저장 시 ID 가 할당된다
        when(saveAccountPort.save(any())).thenAnswer(inv -> {
            SellerDepositAccount saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.assignId(ACCOUNT_ID);
            }
            return saved;
        });
        when(saveEntryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveHoldPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveShortfallPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DepositService(loadAccountPort, saveAccountPort, saveEntryPort,
                loadHoldPort, saveHoldPort, loadShortfallPort, saveShortfallPort, publishEventPort);
    }

    /** 지정 잔고를 가진 영속 계좌. */
    private SellerDepositAccount account(String available, String locked) {
        BigDecimal av = new BigDecimal(available);
        BigDecimal lo = new BigDecimal(locked);
        LocalDateTime now = LocalDateTime.now();
        return SellerDepositAccount.rehydrate(ACCOUNT_ID, SELLER_ID, av, lo, av.add(lo), 0L, now, now);
    }

    private DepositHold hold(String amount) {
        DepositHold h = DepositHold.place(ACCOUNT_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                new BigDecimal(amount), LocalDateTime.now().plusDays(3));
        h.assignId(99L);
        return h;
    }

    private List<DepositEntry> capturedEntries() {
        ArgumentCaptor<DepositEntry> captor = ArgumentCaptor.forClass(DepositEntry.class);
        verify(saveEntryPort, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private DepositEntry entryOfType(DepositEntryType type) {
        return capturedEntries().stream()
                .filter(e -> e.getEntryType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type + " 엔트리가 기록되지 않았습니다"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // credit / debit
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("credit — 정산 확정 입금")
    class CreditTests {

        @Test
        @DisplayName("계좌가 없으면 개설한 뒤 입금하고 CREDIT 엔트리를 남긴다")
        void credit_opensAccountWhenAbsent() {
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.empty());

            service.credit(SELLER_ID, new BigDecimal("100000"), "STL-1", "SETTLEMENT");

            ArgumentCaptor<SellerDepositAccount> captor =
                    ArgumentCaptor.forClass(SellerDepositAccount.class);
            verify(saveAccountPort, times(2)).save(captor.capture());
            SellerDepositAccount saved = captor.getAllValues().get(1);
            assertThat(saved.getAvailable()).isEqualByComparingTo("100000");
            assertThat(saved.getTotal()).isEqualByComparingTo("100000");

            assertThat(entryOfType(DepositEntryType.CREDIT).getReferenceId()).isEqualTo("STL-1");
            verify(publishEventPort).publishBalanceChanged(any(), eq("CREDIT"));
        }

        @Test
        @DisplayName("기존 계좌가 있으면 그대로 입금한다")
        void credit_usesExistingAccount() {
            SellerDepositAccount existing = account("50000", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(existing));

            service.credit(SELLER_ID, new BigDecimal("10000"), "STL-2", "SETTLEMENT");

            assertThat(existing.getAvailable()).isEqualByComparingTo("60000");
            verify(saveAccountPort, times(1)).save(existing);
        }
    }

    @Nested
    @DisplayName("debit — payout 출금")
    class DebitTests {

        @Test
        @DisplayName("available 에서 차감하고 DEBIT 엔트리를 남긴다")
        void debit_reducesAvailable() {
            SellerDepositAccount existing = account("50000", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(existing));

            service.debit(SELLER_ID, new BigDecimal("20000"), "PAY-1", "PAYOUT");

            assertThat(existing.getAvailable()).isEqualByComparingTo("30000");
            assertThat(entryOfType(DepositEntryType.DEBIT).getReferenceId()).isEqualTo("PAY-1");
            verify(publishEventPort).publishBalanceChanged(any(), eq("DEBIT"));
        }

        @Test
        @DisplayName("계좌가 없으면 출금할 수 없다")
        void debit_rejectsMissingAccount() {
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.debit(SELLER_ID, BigDecimal.TEN, "PAY-X", "PAYOUT"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("available 을 초과하는 출금은 도메인이 거부한다")
        void debit_rejectsOverAvailable() {
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID))
                    .thenReturn(Optional.of(account("1000", "0")));

            assertThatThrownBy(() ->
                    service.debit(SELLER_ID, new BigDecimal("2000"), "PAY-Y", "PAYOUT"))
                    .isInstanceOf(InsufficientDepositException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // placeHold
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("placeHold — 재원 선점")
    class PlaceHoldTests {

        @Test
        @DisplayName("available→locked 이동 후 HOLD 엔트리를 남긴다")
        void placeHold_locksFunds() {
            SellerDepositAccount existing = account("50000", "0");
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(existing));

            DepositHold placed = service.placeHold(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION,
                    REF, new BigDecimal("30000"), LocalDateTime.now().plusDays(1));

            assertThat(existing.getAvailable()).isEqualByComparingTo("20000");
            assertThat(existing.getLocked()).isEqualByComparingTo("30000");
            assertThat(existing.getTotal()).isEqualByComparingTo("50000");
            assertThat(placed.getStatus()).isEqualTo(DepositHoldStatus.ACTIVE);
            assertThat(entryOfType(DepositEntryType.HOLD).getReferenceId()).isEqualTo(REF);
            verify(publishEventPort).publishHoldPlaced(any(), any());
        }

        @Test
        @DisplayName("만료시각을 주지 않으면 기본 TTL 로 hold 를 건다")
        void placeHold_defaultsExpiry() {
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID))
                    .thenReturn(Optional.of(account("50000", "0")));

            DepositHold placed = service.placeHold(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION,
                    REF, new BigDecimal("1000"), null);

            assertThat(placed.getExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("동일 (holderType, holderReference) 재요청은 멱등 — 기존 hold 를 반환하고 잔고를 건드리지 않는다")
        void placeHold_isIdempotent() {
            DepositHold existingHold = hold("30000");
            when(loadHoldPort.findByHolderTypeAndReference(DepositHolderType.CARD_AUTHORIZATION, REF))
                    .thenReturn(Optional.of(existingHold));

            DepositHold result = service.placeHold(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION,
                    REF, new BigDecimal("30000"), null);

            assertThat(result).isSameAs(existingHold);
            verify(saveAccountPort, never()).save(any());
            verify(saveEntryPort, never()).save(any());
            verify(saveHoldPort, never()).save(any());
            verify(publishEventPort, never()).publishHoldPlaced(any(), any());
            verify(loadAccountPort, never()).findBySellerIdForUpdate(any());
        }

        @Test
        @DisplayName("available 을 초과하는 hold 는 도메인이 거부한다")
        void placeHold_rejectsOverAvailable() {
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID))
                    .thenReturn(Optional.of(account("1000", "0")));

            assertThatThrownBy(() -> service.placeHold(SELLER_ID,
                    DepositHolderType.CARD_AUTHORIZATION, REF, new BigDecimal("5000"), null))
                    .isInstanceOf(InsufficientDepositException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // applyOffset — 혼합 모델 (C)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("applyOffset — 상계(혼합 모델)")
    class ApplyOffsetTests {

        @Test
        @DisplayName("hold 전액 캡처 — locked 와 total 이 줄고 available 은 불변")
        void offset_fullCapture() {
            SellerDepositAccount acc = account("20000", "30000");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            DepositHold h = hold("30000");
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.of(h));

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("30000"), 0, OffsetDateTime.now());

            assertThat(acc.getLocked()).isEqualByComparingTo("0");
            assertThat(acc.getAvailable()).isEqualByComparingTo("20000");
            assertThat(acc.getTotal()).isEqualByComparingTo("20000");
            assertThat(h.getStatus()).isEqualTo(DepositHoldStatus.CAPTURED);

            DepositEntry offset = entryOfType(DepositEntryType.OFFSET);
            assertThat(offset.getAmount()).isEqualByComparingTo("30000");
            assertThat(offset.getSourceHoldId()).isEqualTo(99L);
            verify(publishEventPort).publishOffsetApplied(any(), any());
            verify(saveShortfallPort, never()).save(any());
        }

        @Test
        @DisplayName("hold 부분 캡처 — 잔여 locked 는 RELEASE 로 available 에 반환된다")
        void offset_partialCaptureReleasesRemainder() {
            SellerDepositAccount acc = account("0", "30000");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            DepositHold h = hold("30000");
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.of(h));

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("10000"), 0, OffsetDateTime.now());

            assertThat(acc.getLocked()).isEqualByComparingTo("0");
            assertThat(acc.getAvailable()).isEqualByComparingTo("20000");
            assertThat(acc.getTotal()).isEqualByComparingTo("20000");
            assertThat(h.getStatus()).isEqualTo(DepositHoldStatus.RELEASED);

            assertThat(entryOfType(DepositEntryType.RELEASE).getAmount())
                    .isEqualByComparingTo("20000");
            assertThat(entryOfType(DepositEntryType.OFFSET).getAmount())
                    .isEqualByComparingTo("10000");
            verify(publishEventPort).publishHoldReleased(any(), any());
            verify(saveShortfallPort, never()).save(any());
        }

        @Test
        @DisplayName("hold 가 없는 늦은 청구는 available 에서 직접 차감하고 sourceHoldId 를 null 로 남긴다")
        void offset_withoutHoldDeductsAvailable() {
            SellerDepositAccount acc = account("50000", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("30000"), 0, OffsetDateTime.now());

            assertThat(acc.getAvailable()).isEqualByComparingTo("20000");
            assertThat(acc.getLocked()).isEqualByComparingTo("0");

            DepositEntry offset = entryOfType(DepositEntryType.OFFSET);
            assertThat(offset.getAmount()).isEqualByComparingTo("30000");
            assertThat(offset.getSourceHoldId()).isNull();
            verify(saveShortfallPort, never()).save(any());
        }

        @Test
        @DisplayName("만료된 hold 는 없는 것으로 보고 available 경로를 탄다")
        void offset_expiredHoldFallsBackToAvailable() {
            SellerDepositAccount acc = account("50000", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            DepositHold expired = hold("30000");
            expired.expire();
            when(loadHoldPort.findByHolderTypeAndReference(any(), any()))
                    .thenReturn(Optional.of(expired));

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("30000"), 0, OffsetDateTime.now());

            assertThat(acc.getAvailable()).isEqualByComparingTo("20000");
            assertThat(entryOfType(DepositEntryType.OFFSET).getSourceHoldId()).isNull();
            verify(saveHoldPort, never()).save(any());
        }

        @Test
        @DisplayName("hold 로 부족하면 available 을 이어서 끌어다 쓴다")
        void offset_holdThenAvailable() {
            SellerDepositAccount acc = account("20000", "10000");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            DepositHold h = hold("10000");
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.of(h));

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("25000"), 0, OffsetDateTime.now());

            assertThat(acc.getLocked()).isEqualByComparingTo("0");
            assertThat(acc.getAvailable()).isEqualByComparingTo("5000");
            assertThat(entryOfType(DepositEntryType.OFFSET).getAmount()).isEqualByComparingTo("25000");
            verify(saveShortfallPort, never()).save(any());
        }

        @Test
        @DisplayName("재원이 모자라면 전액 거부가 아니라 가능한 만큼 상계하고 부족분을 영속화한다")
        void offset_insufficientRecordsShortfall() {
            SellerDepositAccount acc = account("5000", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("30000"), 0, OffsetDateTime.now());

            // 가능한 만큼(5000)은 실제로 상계 — 남겨두면 payout 으로 빠져나간다
            assertThat(acc.getAvailable()).isEqualByComparingTo("0");
            assertThat(entryOfType(DepositEntryType.OFFSET).getAmount()).isEqualByComparingTo("5000");

            ArgumentCaptor<DepositOffsetShortfall> captor =
                    ArgumentCaptor.forClass(DepositOffsetShortfall.class);
            verify(saveShortfallPort).save(captor.capture());
            DepositOffsetShortfall shortfall = captor.getValue();
            assertThat(shortfall.getRequestedAmount()).isEqualByComparingTo("30000");
            assertThat(shortfall.getAppliedAmount()).isEqualByComparingTo("5000");
            assertThat(shortfall.getShortfallAmount()).isEqualByComparingTo("25000");
            assertThat(shortfall.getStatus()).isEqualTo(DepositShortfallStatus.OPEN);
            verify(publishEventPort).publishOffsetShortfall(any());
        }

        @Test
        @DisplayName("가용 재원이 0 이면 OFFSET 엔트리 없이 전액 부족분만 기록한다")
        void offset_zeroAppliedRecordsFullShortfall() {
            SellerDepositAccount acc = account("0", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("30000"), 0, OffsetDateTime.now());

            verify(saveEntryPort, never()).save(any());
            verify(publishEventPort, never()).publishOffsetApplied(any(), any());

            ArgumentCaptor<DepositOffsetShortfall> captor =
                    ArgumentCaptor.forClass(DepositOffsetShortfall.class);
            verify(saveShortfallPort).save(captor.capture());
            assertThat(captor.getValue().getShortfallAmount()).isEqualByComparingTo("30000");
        }

        @Test
        @DisplayName("occurredAt 이 없으면 현재 시각으로 부족분을 기록한다")
        void offset_nullOccurredAtDefaultsToNow() {
            SellerDepositAccount acc = account("0", "0");
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));
            when(loadHoldPort.findByHolderTypeAndReference(any(), any())).thenReturn(Optional.empty());

            service.applyOffset(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                    new BigDecimal("100"), 3, null);

            ArgumentCaptor<DepositOffsetShortfall> captor =
                    ArgumentCaptor.forClass(DepositOffsetShortfall.class);
            verify(saveShortfallPort).save(captor.capture());
            assertThat(captor.getValue().getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("계좌가 없으면 상계할 수 없다")
        void offset_rejectsMissingAccount() {
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.applyOffset(SELLER_ID,
                    DepositHolderType.CARD_AUTHORIZATION, REF, BigDecimal.TEN, 0, OffsetDateTime.now()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 조회
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findBySellerId 는 조회 포트에 위임한다")
    void findBySellerId_delegates() {
        SellerDepositAccount acc = account("1000", "0");
        when(loadAccountPort.findBySellerId(SELLER_ID)).thenReturn(Optional.of(acc));

        assertThat(service.findBySellerId(SELLER_ID)).contains(acc);
        verify(loadAccountPort).findBySellerId(SELLER_ID);
    }
}
