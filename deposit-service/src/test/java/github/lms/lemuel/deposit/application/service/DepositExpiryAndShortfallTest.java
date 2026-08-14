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
 * 만료 hold 회수(G-4)와 부족분 해소(G-5) 계약.
 *
 * <p>두 경로는 같은 병을 앓고 있었다 — <b>회수·해소의 재료는 다 있는데 그것을 도는 주체가 없었다.</b>
 * 그래서 실패가 조용하다: 만료된 hold 는 locked 를 계속 잡고, 부족분은 기록된 채 방치된다.
 * 둘 다 예외를 던지지 않으므로 테스트가 유일한 감시자다.
 */
class DepositExpiryAndShortfallTest {

    private static final Long SELLER_ID = 42L;
    private static final Long ACCOUNT_ID = 1L;

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

        when(saveAccountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveEntryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveHoldPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveShortfallPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DepositService(loadAccountPort, saveAccountPort, saveEntryPort,
                loadHoldPort, saveHoldPort, loadShortfallPort, saveShortfallPort, publishEventPort,
                mock(DepositProofGate.class));
    }

    /** available/locked 를 직접 지정한 계좌. */
    private static SellerDepositAccount account(String available, String locked) {
        return SellerDepositAccount.rehydrate(
                ACCOUNT_ID, SELLER_ID,
                new BigDecimal(available), new BigDecimal(locked),
                new BigDecimal(available).add(new BigDecimal(locked)),
                0L, LocalDateTime.now(), LocalDateTime.now());
    }

    private static DepositHold activeHold(String amount, LocalDateTime expiresAt) {
        DepositHold hold = DepositHold.place(ACCOUNT_ID, DepositHolderType.CARD_AUTHORIZATION, "AUTH-1",
                new BigDecimal(amount), expiresAt);
        hold.assignId(10L);
        return hold;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // G-4 만료 hold 회수
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("만료 hold 회수")
    class ExpireDueHolds {

        private final LocalDateTime cutoff = LocalDateTime.of(2026, 8, 13, 4, 0);

        @Test
        @DisplayName("만료된 hold 를 EXPIRED 로 닫고 잔여 선점액을 available 로 되돌린다")
        void releasesLockedBackToAvailable() {
            SellerDepositAccount acc = account("1000", "3000");
            DepositHold hold = activeHold("3000", cutoff.minusHours(1));
            when(loadHoldPort.findExpiredStillHolding(cutoff)).thenReturn(List.of(hold));
            when(loadAccountPort.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(acc));

            int expired = service.expireDueHolds(cutoff);

            assertThat(expired).isEqualTo(1);
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.EXPIRED);
            // 되돌아온 것은 잔여(remaining)지 원금이 아니다 — 부분 캡처된 hold 에서 갈린다.
            assertThat(acc.getLocked()).isEqualByComparingTo("0");
            assertThat(acc.getAvailable()).isEqualByComparingTo("4000");
            // total 은 변하지 않는다 — 선점 해제는 계좌 안에서의 이동이지 입출금이 아니다.
            assertThat(acc.getTotal()).isEqualByComparingTo("4000");
        }

        @Test
        @DisplayName("부분 캡처된 hold 도 회수한다 — ACTIVE 만 보면 잔여가 영구히 잠긴다")
        void releasesOnlyRemainingOfPartiallyCaptured() {
            SellerDepositAccount acc = account("1000", "3000");
            DepositHold hold = activeHold("3000", cutoff.minusHours(1));
            hold.capture(new BigDecimal("2000"));       // remaining 1000, PARTIALLY_CAPTURED
            acc.captureFromLocked(new BigDecimal("2000"));
            when(loadHoldPort.findExpiredStillHolding(cutoff)).thenReturn(List.of(hold));
            when(loadAccountPort.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(acc));

            service.expireDueHolds(cutoff);

            // 종단 상태는 RELEASED — "쓰이다 남은 잔여를 놓았다"는 EXPIRED("한 번도 안 쓰였다")와
            // 사후 대사에서 의미가 다르다.
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.RELEASED);
            assertThat(acc.getLocked()).isEqualByComparingTo("0");
            assertThat(acc.getAvailable()).isEqualByComparingTo("2000");
            // 부분 캡처분은 이미 계좌를 떠났다 — total 이 줄어 있어야 한다.
            assertThat(acc.getTotal()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("RELEASE 엔트리와 hold_released 이벤트를 남긴다 — 잔고만 바뀌면 사후에 원인을 못 짚는다")
        void recordsEntryAndEvent() {
            SellerDepositAccount acc = account("0", "500");
            DepositHold hold = activeHold("500", cutoff.minusMinutes(1));
            when(loadHoldPort.findExpiredStillHolding(cutoff)).thenReturn(List.of(hold));
            when(loadAccountPort.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(acc));

            service.expireDueHolds(cutoff);

            ArgumentCaptor<DepositEntry> entry = ArgumentCaptor.forClass(DepositEntry.class);
            verify(saveEntryPort).save(entry.capture());
            assertThat(entry.getValue().getEntryType()).isEqualTo(DepositEntryType.RELEASE);
            assertThat(entry.getValue().getAmount()).isEqualByComparingTo("500");
            verify(publishEventPort).publishHoldReleased(hold, acc);
        }

        @Test
        @DisplayName("한 건이 실패해도 나머지를 회수한다 — 전건 한 트랜잭션이면 락 경합 하나가 그날 회수를 전부 되돌린다")
        void oneFailureDoesNotStopTheRest() {
            SellerDepositAccount acc = account("0", "500");
            DepositHold broken = activeHold("500", cutoff.minusHours(2));
            DepositHold good = DepositHold.place(ACCOUNT_ID, DepositHolderType.CARD_AUTHORIZATION, "AUTH-2",
                    new BigDecimal("500"), cutoff.minusHours(1));
            good.assignId(11L);

            when(loadHoldPort.findExpiredStillHolding(cutoff)).thenReturn(List.of(broken, good));
            // 첫 건은 계좌를 못 찾아 실패, 둘째 건은 정상
            when(loadAccountPort.findByIdForUpdate(ACCOUNT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(acc));

            int expired = service.expireDueHolds(cutoff);

            assertThat(expired).isEqualTo(1);
            assertThat(broken.getStatus()).isEqualTo(DepositHoldStatus.ACTIVE);   // 그대로 남아 다음 회차에 재시도
            assertThat(good.getStatus()).isEqualTo(DepositHoldStatus.EXPIRED);
        }

        @Test
        @DisplayName("만료 대상이 없으면 아무것도 건드리지 않는다")
        void noopWhenNothingDue() {
            when(loadHoldPort.findExpiredStillHolding(cutoff)).thenReturn(List.of());

            assertThat(service.expireDueHolds(cutoff)).isZero();

            verifyNoInteractions(saveAccountPort, saveHoldPort, saveEntryPort, publishEventPort);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // G-5 부족분 해소
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("부족분 해소")
    class Shortfall {

        private DepositOffsetShortfall openShortfall(String requested, String applied) {
            DepositOffsetShortfall s = DepositOffsetShortfall.open(
                    SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, "CAP-1",
                    new BigDecimal(requested), new BigDecimal(applied), null,
                    OffsetDateTime.now());
            s.assignId(99L);
            return s;
        }

        @Test
        @DisplayName("가용 잔고가 충분하면 실제로 차감하고 RESOLVED 로 닫는다")
        void resolveDebitsAvailable() {
            SellerDepositAccount acc = account("5000", "0");
            DepositOffsetShortfall s = openShortfall("3000", "1000");   // 부족분 2000
            when(loadShortfallPort.findById(99L)).thenReturn(Optional.of(s));
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));

            BigDecimal applied = service.resolveFromAvailable(99L);

            assertThat(applied).isEqualByComparingTo("2000");
            assertThat(s.getStatus()).isEqualTo(DepositShortfallStatus.RESOLVED);
            // 상태만 바꾸고 돈을 그대로 두면 장부와 잔고가 어긋난다 — 실제 차감을 못 박는다.
            assertThat(acc.getAvailable()).isEqualByComparingTo("3000");
            assertThat(acc.getTotal()).isEqualByComparingTo("3000");
        }

        @Test
        @DisplayName("해소는 OFFSET 엔트리로 남는다 — 부족분이 언제 무엇으로 덮였는지 추적 가능해야 한다")
        void resolveRecordsOffsetEntry() {
            SellerDepositAccount acc = account("5000", "0");
            when(loadShortfallPort.findById(99L)).thenReturn(Optional.of(openShortfall("3000", "1000")));
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));

            service.resolveFromAvailable(99L);

            ArgumentCaptor<DepositEntry> entry = ArgumentCaptor.forClass(DepositEntry.class);
            verify(saveEntryPort).save(entry.capture());
            assertThat(entry.getValue().getEntryType()).isEqualTo(DepositEntryType.OFFSET);
            assertThat(entry.getValue().getAmount()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("가용액이 부족분에 못 미치면 아무것도 바꾸지 않고 거부한다 — 부분 해소는 부족분을 갈래로 만든다")
        void resolveRejectsWhenInsufficient() {
            SellerDepositAccount acc = account("500", "0");
            DepositOffsetShortfall s = openShortfall("3000", "1000");   // 부족분 2000 > 가용 500
            when(loadShortfallPort.findById(99L)).thenReturn(Optional.of(s));
            when(loadAccountPort.findBySellerIdForUpdate(SELLER_ID)).thenReturn(Optional.of(acc));

            assertThatThrownBy(() -> service.resolveFromAvailable(99L))
                    .isInstanceOf(InsufficientDepositException.class);

            assertThat(s.getStatus()).isEqualTo(DepositShortfallStatus.OPEN);
            assertThat(acc.getAvailable()).isEqualByComparingTo("500");
            verify(saveEntryPort, never()).save(any());
        }

        @Test
        @DisplayName("없는 부족분은 IllegalArgumentException — 존재하지 않는 대상에 성공을 돌려주지 않는다")
        void resolveUnknownIdThrows() {
            when(loadShortfallPort.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveFromAvailable(404L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("상각은 잔고를 건드리지 않는다 — 돈이 아니라 회수 포기라는 판단의 기록이다")
        void writeOffLeavesBalanceUntouched() {
            DepositOffsetShortfall s = openShortfall("3000", "1000");
            when(loadShortfallPort.findById(99L)).thenReturn(Optional.of(s));

            service.writeOff(99L);

            assertThat(s.getStatus()).isEqualTo(DepositShortfallStatus.WRITTEN_OFF);
            verifyNoInteractions(saveAccountPort, saveEntryPort);
        }

        @Test
        @DisplayName("OPEN 부족분 목록을 그대로 돌려준다 — 지표·콘솔의 단일 입력")
        void findsOpenShortfalls() {
            DepositOffsetShortfall s = openShortfall("3000", "1000");
            when(loadShortfallPort.findByStatus(DepositShortfallStatus.OPEN)).thenReturn(List.of(s));

            assertThat(service.findOpenShortfalls()).containsExactly(s);
        }
    }
}
