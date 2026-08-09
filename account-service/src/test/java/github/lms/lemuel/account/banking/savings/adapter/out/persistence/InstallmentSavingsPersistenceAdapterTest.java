package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 적금 영속 어댑터 단위 테스트 — DB 없이 리포지터리 mock 으로 매핑·append-only·N+1 회피를 검증한다.
 * ({@code @DataJpaTest} 를 쓰지 않는 것은 이 어댑터의 책임이 "두 테이블을 애그리거트로 접합"하는
 * 순수 로직이기 때문이다 — 그 로직은 실제 DB 없이 전부 관찰 가능하다.)
 */
@ExtendWith(MockitoExtension.class)
class InstallmentSavingsPersistenceAdapterTest {

    private static final LocalDate OPENED_ON = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY = LocalDate.of(2026, 4, 1);
    private static final BigDecimal MONTHLY = new BigDecimal("100000.00");

    @Mock
    private InstallmentSavingsRepository savingsRepository;

    @Mock
    private SavingsInstallmentRepository installmentRepository;

    @InjectMocks
    private InstallmentSavingsPersistenceAdapter adapter;

    @Captor
    private ArgumentCaptor<List<SavingsInstallmentJpaEntity>> rowsCaptor;

    private static InstallmentSavingsJpaEntity entity(Long id) {
        return new InstallmentSavingsJpaEntity(id, "42", "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, new BigDecimal("0.036500"), new BigDecimal("0.007300"),
                3, OPENED_ON, MATURITY, SavingsStatus.ACTIVE, null, null, null);
    }

    private static SavingsInstallmentJpaEntity row(Long id, Long savingsId, int roundNo, LocalDate paidOn) {
        LocalDate due = OPENED_ON.plusMonths(roundNo - 1L);
        int overdue = paidOn.isAfter(due) ? (int) (paidOn.toEpochDay() - due.toEpochDay()) : 0;
        return new SavingsInstallmentJpaEntity(id, savingsId, roundNo, MONTHLY, due, paidOn, overdue);
    }

    private static InstallmentSavings domain(Long id, List<SavingsInstallment> installments) {
        return InstallmentSavings.reconstitute(id, "42", "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, OPENED_ON, MATURITY, SavingsStatus.ACTIVE, null, null, null, installments);
    }

    @Test
    void 단건_조회는_계약과_회차를_하나의_애그리거트로_접합한다() {
        when(savingsRepository.findById(9L)).thenReturn(Optional.of(entity(9L)));
        when(installmentRepository.findBySavingsIdOrderByRoundNoAsc(9L)).thenReturn(List.of(
                row(1L, 9L, 1, OPENED_ON),
                row(2L, 9L, 2, LocalDate.of(2026, 2, 11))));

        Optional<InstallmentSavings> found = adapter.findById(9L);

        assertThat(found).isPresent();
        InstallmentSavings savings = found.orElseThrow();
        assertThat(savings.getId()).isEqualTo(9L);
        assertThat(savings.getDepositorId()).isEqualTo("42");
        assertThat(savings.getSavingsType()).isEqualTo(SavingsType.FIXED);
        assertThat(savings.getStatus()).isEqualTo(SavingsStatus.ACTIVE);
        assertThat(savings.getMaturityDate()).isEqualTo(MATURITY);
        assertThat(savings.getInstallments()).extracting(SavingsInstallment::getRound)
                .containsExactly(1, 2);
        assertThat(savings.getInstallments().get(1).getOverdueDays()).isEqualTo(10);
        assertThat(savings.totalPaidAmount()).isEqualByComparingTo("200000");
    }

    @Test
    void 없는_계약은_빈_Optional_이다() {
        when(savingsRepository.findById(9L)).thenReturn(Optional.empty());

        assertThat(adapter.findById(9L)).isEmpty();
        verify(installmentRepository, never()).findBySavingsIdOrderByRoundNoAsc(any());
    }

    @Test
    void 목록_조회는_회차를_IN_한_번으로_읽어_N_플러스_1_을_피한다() {
        when(savingsRepository.findByDepositorIdOrderByOpenedOnDescIdDesc("42"))
                .thenReturn(List.of(entity(9L), entity(10L)));
        when(installmentRepository.findBySavingsIdInOrderByRoundNoAsc(List.of(9L, 10L)))
                .thenReturn(List.of(row(1L, 9L, 1, OPENED_ON),
                        row(2L, 10L, 1, OPENED_ON),
                        row(3L, 10L, 2, LocalDate.of(2026, 2, 1))));

        List<InstallmentSavings> result = adapter.findByDepositorId("42");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getInstallments()).hasSize(1);
        assertThat(result.get(1).getInstallments()).hasSize(2);
        // 계약별 개별 조회가 한 번도 일어나지 않아야 N+1 이 아니다
        verify(installmentRepository, never()).findBySavingsIdOrderByRoundNoAsc(any());
    }

    @Test
    void 회차가_하나도_없는_계약도_목록에_빈_회차로_들어간다() {
        when(savingsRepository.findByDepositorIdOrderByOpenedOnDescIdDesc("42"))
                .thenReturn(List.of(entity(9L)));
        when(installmentRepository.findBySavingsIdInOrderByRoundNoAsc(List.of(9L)))
                .thenReturn(List.of());

        List<InstallmentSavings> result = adapter.findByDepositorId("42");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInstallments()).isEmpty();
    }

    @Test
    void 계약이_없으면_회차_조회는_아예_하지_않는다() {
        when(savingsRepository.findByDepositorIdOrderByOpenedOnDescIdDesc("42")).thenReturn(List.of());

        assertThat(adapter.findByDepositorId("42")).isEmpty();
        verify(installmentRepository, never()).findBySavingsIdInOrderByRoundNoAsc(any());
    }

    @Test
    void 저장은_이미_저장된_회차를_다시_쓰지_않는다() {
        // 도메인엔 1·2회차가 있고 DB엔 1회차만 있다 → 새로 넣을 행은 2회차뿐
        when(savingsRepository.saveAndFlush(any())).thenReturn(entity(9L));
        when(installmentRepository.findBySavingsIdOrderByRoundNoAsc(9L))
                .thenReturn(List.of(row(1L, 9L, 1, OPENED_ON)))
                .thenReturn(List.of(row(1L, 9L, 1, OPENED_ON), row(2L, 9L, 2, LocalDate.of(2026, 2, 1))));

        InstallmentSavings saved = adapter.save(domain(9L, List.of(
                SavingsInstallment.reconstitute(1L, 1, MONTHLY, OPENED_ON, OPENED_ON, 0),
                SavingsInstallment.reconstitute(null, 2, MONTHLY,
                        LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1), 0))));

        verify(installmentRepository).saveAll(rowsCaptor.capture());
        Collection<SavingsInstallmentJpaEntity> inserted = rowsCaptor.getValue();
        assertThat(inserted).hasSize(1);
        assertThat(inserted.iterator().next().getRoundNo()).isEqualTo(2);
        assertThat(inserted.iterator().next().getSavingsId()).isEqualTo(9L);
        assertThat(saved.getInstallments()).hasSize(2);
    }

    @Test
    void 새로_넣을_회차가_없으면_저장을_호출하지_않는다() {
        when(savingsRepository.saveAndFlush(any())).thenReturn(entity(9L));
        when(installmentRepository.findBySavingsIdOrderByRoundNoAsc(9L))
                .thenReturn(List.of(row(1L, 9L, 1, OPENED_ON)));

        InstallmentSavings saved = adapter.save(domain(9L, List.of(
                SavingsInstallment.reconstitute(1L, 1, MONTHLY, OPENED_ON, OPENED_ON, 0))));

        verify(installmentRepository, never()).saveAll(any());
        assertThat(saved.getInstallments()).hasSize(1);
    }

    @Test
    void 신규_계약은_저장으로_받은_id_를_회차에_붙인다() {
        when(savingsRepository.saveAndFlush(any())).thenReturn(entity(9L));
        when(installmentRepository.findBySavingsIdOrderByRoundNoAsc(9L))
                .thenReturn(List.of())
                .thenReturn(List.of(row(1L, 9L, 1, OPENED_ON)));

        InstallmentSavings saved = adapter.save(domain(null, List.of(
                SavingsInstallment.reconstitute(null, 1, MONTHLY, OPENED_ON, OPENED_ON, 0))));

        verify(installmentRepository).saveAll(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue().get(0).getSavingsId()).isEqualTo(9L);
        assertThat(rowsCaptor.getValue().get(0).getId()).isNull();   // 새 행은 id 를 지정하지 않는다
        assertThat(saved.getId()).isEqualTo(9L);
    }

    @Test
    void 저장은_도메인_상태를_그대로_엔티티에_옮긴다() {
        when(savingsRepository.saveAndFlush(any())).thenReturn(entity(9L));
        when(installmentRepository.findBySavingsIdOrderByRoundNoAsc(9L)).thenReturn(List.of());

        InstallmentSavings closed = InstallmentSavings.reconstitute(9L, "42", "정액적립 3개월",
                SavingsType.FIXED, MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, OPENED_ON, MATURITY, SavingsStatus.CLOSED, MATURITY,
                new BigDecimal("1800"), new BigDecimal("301800"), List.of());
        adapter.save(closed);

        ArgumentCaptor<InstallmentSavingsJpaEntity> captor =
                ArgumentCaptor.forClass(InstallmentSavingsJpaEntity.class);
        verify(savingsRepository).saveAndFlush(captor.capture());
        InstallmentSavingsJpaEntity passed = captor.getValue();
        assertThat(passed.getId()).isEqualTo(9L);
        assertThat(passed.getStatus()).isEqualTo(SavingsStatus.CLOSED);
        assertThat(passed.getClosedOn()).isEqualTo(MATURITY);
        assertThat(passed.getSettledInterest()).isEqualByComparingTo("1800");
        assertThat(passed.getPayoutAmount()).isEqualByComparingTo("301800");
        assertThat(passed.getTermMonths()).isEqualTo(3);
        assertThat(passed.getPaymentLimit()).isNull();
    }
}
