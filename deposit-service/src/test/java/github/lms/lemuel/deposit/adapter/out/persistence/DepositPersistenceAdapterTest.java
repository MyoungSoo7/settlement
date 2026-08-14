package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 예치금 잔고·hold 영속 어댑터.
 *
 * <p>이 어댑터가 지키는 계약은 셋이다. ① 신규 저장 뒤 <b>도메인 객체에 ID 를 채워 준다</b>
 * (안 채우면 같은 요청 안에서 두 번 저장돼 잔고가 두 번 움직인다), ② 기존 행은 새로 만들지 않고
 * 읽어서 갱신한다(낙관적 락 버전 보존), ③ 없는 행을 갱신하려 하면 조용히 새로 만들지 않고 실패한다.
 */
class DepositPersistenceAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);

    @Nested
    @DisplayName("계좌")
    class Accounts {

        private SpringDataDepositAccountRepository repo;
        private DepositAccountPersistenceAdapter adapter;

        @BeforeEach
        void setUp() {
            repo = mock(SpringDataDepositAccountRepository.class);
            adapter = new DepositAccountPersistenceAdapter(repo);
        }

        private DepositAccountJpaEntity entity() {
            return new DepositAccountJpaEntity(7L, new BigDecimal("3000000.00"),
                    new BigDecimal("500000.00"), new BigDecimal("3500000.00"));
        }

        @Test
        @DisplayName("셀러로 조회하면 도메인으로 복원한다")
        void findBySellerId() {
            when(repo.findBySellerId(7L)).thenReturn(Optional.of(entity()));

            SellerDepositAccount account = adapter.findBySellerId(7L).orElseThrow();

            assertThat(account.getSellerId()).isEqualTo(7L);
            assertThat(account.getAvailable()).isEqualByComparingTo("3000000.00");
            assertThat(account.getLocked()).isEqualByComparingTo("500000.00");
            assertThat(account.getTotal()).isEqualByComparingTo("3500000.00");
        }

        @Test
        @DisplayName("없으면 빈 Optional")
        void findMissing() {
            when(repo.findBySellerId(9L)).thenReturn(Optional.empty());

            assertThat(adapter.findBySellerId(9L)).isEmpty();
        }

        @Test
        @DisplayName("잔고를 움직이는 경로는 잠금 조회를 쓴다 (셀러·계좌 두 축)")
        void findsForUpdate() {
            when(repo.findBySellerIdForUpdate(7L)).thenReturn(Optional.of(entity()));
            when(repo.findByIdForUpdate(1L)).thenReturn(Optional.of(entity()));

            assertThat(adapter.findBySellerIdForUpdate(7L)).isPresent();
            assertThat(adapter.findByIdForUpdate(1L)).isPresent();
            verify(repo).findBySellerIdForUpdate(7L);
            verify(repo).findByIdForUpdate(1L);
        }

        @Test
        @DisplayName("신규 계좌는 새 행으로 저장하고 도메인에 ID 를 채워 준다")
        void savesNewAndAssignsId() {
            SellerDepositAccount account = SellerDepositAccount.open(7L);
            when(repo.save(any(DepositAccountJpaEntity.class))).thenAnswer(i -> {
                DepositAccountJpaEntity e = i.getArgument(0);
                // 실제 저장은 IDENTITY 로 ID 를 채워 돌려준다 — 그 상황을 재현한다
                DepositAccountJpaEntity saved = mock(DepositAccountJpaEntity.class);
                when(saved.getId()).thenReturn(42L);
                when(saved.getSellerId()).thenReturn(e.getSellerId());
                return saved;
            });

            SellerDepositAccount result = adapter.save(account);

            assertThat(result.getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("기존 계좌는 읽어서 잔고만 갱신한다 (새 행을 만들지 않는다)")
        void updatesExisting() {
            SellerDepositAccount account = SellerDepositAccount.rehydrate(1L, 7L,
                    new BigDecimal("4000000.00"), new BigDecimal("0.00"), new BigDecimal("4000000.00"),
                    3L, NOW, NOW);
            DepositAccountJpaEntity existing = entity();
            when(repo.findById(1L)).thenReturn(Optional.of(existing));
            when(repo.save(any(DepositAccountJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

            adapter.save(account);

            ArgumentCaptor<DepositAccountJpaEntity> captor =
                    ArgumentCaptor.forClass(DepositAccountJpaEntity.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getAvailable()).isEqualByComparingTo("4000000.00");
            assertThat(captor.getValue().getTotal()).isEqualByComparingTo("4000000.00");
        }

        @Test
        @DisplayName("없는 계좌를 갱신하려 하면 조용히 새로 만들지 않고 실패한다")
        void failsWhenUpdatingMissingRow() {
            SellerDepositAccount ghost = SellerDepositAccount.rehydrate(99L, 7L,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, NOW, NOW);
            when(repo.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.save(ghost))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("계좌를 찾을 수 없습니다");
        }
    }

    @Nested
    @DisplayName("hold")
    class Holds {

        private SpringDataDepositHoldRepository repo;
        private DepositHoldPersistenceAdapter adapter;

        @BeforeEach
        void setUp() {
            repo = mock(SpringDataDepositHoldRepository.class);
            adapter = new DepositHoldPersistenceAdapter(repo);
        }

        private DepositHoldJpaEntity entity() {
            return new DepositHoldJpaEntity(1L, DepositHolderType.CARD_AUTHORIZATION, "AUTH-1",
                    new BigDecimal("500000.00"), new BigDecimal("500000.00"),
                    DepositHoldStatus.ACTIVE, NOW.plusDays(1));
        }

        @Test
        @DisplayName("홀더 종류·참조로 찾은 hold 를 도메인으로 복원한다")
        void findsByHolderReference() {
            when(repo.findByHolderTypeAndHolderReference(DepositHolderType.CARD_AUTHORIZATION, "AUTH-1"))
                    .thenReturn(Optional.of(entity()));

            DepositHold hold = adapter
                    .findByHolderTypeAndReference(DepositHolderType.CARD_AUTHORIZATION, "AUTH-1")
                    .orElseThrow();

            assertThat(hold.getHolderReference()).isEqualTo("AUTH-1");
            assertThat(hold.getOriginalAmount()).isEqualByComparingTo("500000.00");
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.ACTIVE);
        }

        @Test
        @DisplayName("만료 회수 대상은 ACTIVE·부분소진 두 상태만 훑는다")
        void findsExpiredStillHolding() {
            when(repo.findByStatusInAndExpiresAtBefore(
                    List.of(DepositHoldStatus.ACTIVE, DepositHoldStatus.PARTIALLY_CAPTURED), NOW))
                    .thenReturn(List.of(entity()));

            assertThat(adapter.findExpiredStillHolding(NOW)).hasSize(1);
            verify(repo).findByStatusInAndExpiresAtBefore(
                    List.of(DepositHoldStatus.ACTIVE, DepositHoldStatus.PARTIALLY_CAPTURED), NOW);
        }

        @Test
        @DisplayName("신규 hold 는 저장 후 도메인에 ID 를 채워 준다")
        void savesNewAndAssignsId() {
            DepositHold hold = DepositHold.place(1L, DepositHolderType.CARD_AUTHORIZATION, "AUTH-2",
                    new BigDecimal("100000.00"), NOW.plusDays(1));
            when(repo.save(any(DepositHoldJpaEntity.class))).thenAnswer(i -> {
                DepositHoldJpaEntity saved = mock(DepositHoldJpaEntity.class);
                when(saved.getId()).thenReturn(77L);
                return saved;
            });

            assertThat(adapter.save(hold).getId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("기존 hold 는 잔여금액·상태만 갱신한다")
        void updatesExisting() {
            DepositHold hold = DepositHold.rehydrate(11L, 1L, DepositHolderType.CARD_AUTHORIZATION,
                    "AUTH-1", new BigDecimal("500000.00"), new BigDecimal("200000.00"),
                    DepositHoldStatus.PARTIALLY_CAPTURED, NOW.plusDays(1), NOW, NOW, 1L);
            DepositHoldJpaEntity existing = entity();
            when(repo.findById(11L)).thenReturn(Optional.of(existing));
            when(repo.save(any(DepositHoldJpaEntity.class))).thenAnswer(i -> i.getArgument(0));

            adapter.save(hold);

            assertThat(existing.getRemainingAmount()).isEqualByComparingTo("200000.00");
            assertThat(existing.getStatus()).isEqualTo(DepositHoldStatus.PARTIALLY_CAPTURED);
        }

        @Test
        @DisplayName("없는 hold 를 갱신하려 하면 실패한다")
        void failsWhenUpdatingMissingRow() {
            DepositHold ghost = DepositHold.rehydrate(99L, 1L, DepositHolderType.MANUAL, "M-1",
                    BigDecimal.ONE, BigDecimal.ONE, DepositHoldStatus.ACTIVE, null, NOW, NOW, 0L);
            when(repo.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.save(ghost))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Hold 를 찾을 수 없습니다");
        }
    }
}
