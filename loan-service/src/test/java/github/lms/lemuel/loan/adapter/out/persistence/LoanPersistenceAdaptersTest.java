package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CompanyReputation;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영속성 어댑터 단위 테스트 — repository 를 Mockito 로 목킹해 도메인↔엔티티 매핑(toEntity/toDomain)과
 * 위임 로직을 검증한다. (@DataJpaTest 없이 매핑 라인 커버)
 */
@ExtendWith(MockitoExtension.class)
class LoanPersistenceAdaptersTest {

    @Nested
    @ExtendWith(MockitoExtension.class)
    class CorporateLoan_ {

        @Mock CorporateLoanRepository repository;

        private CorporateLoanPersistenceAdapter adapter() {
            return new CorporateLoanPersistenceAdapter(repository);
        }

        private CorporateLoanJpaEntity entity(long id) {
            return new CorporateLoanJpaEntity(id, "005930", "삼성전자",
                    new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                    30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now(), 42L);
        }

        @Test
        @DisplayName("save 는 도메인→엔티티→도메인 왕복 매핑 후 저장한다")
        void save_roundTrips() {
            CorporateLoan loan = CorporateLoan.reconstitute(7L, "005930", "삼성전자",
                    new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                    30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now());
            when(repository.save(any())).thenReturn(entity(7L));

            CorporateLoan saved = adapter().save(loan);

            ArgumentCaptor<CorporateLoanJpaEntity> captor = ArgumentCaptor.forClass(CorporateLoanJpaEntity.class);
            verify(repository).save(captor.capture());
            CorporateLoanJpaEntity persisted = captor.getValue();
            assertThat(persisted.getStockCode()).isEqualTo("005930");
            assertThat(persisted.getCorpName()).isEqualTo("삼성전자");
            assertThat(persisted.getPrincipal()).isEqualByComparingTo("1000000");
            assertThat(persisted.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
            assertThat(saved.getId()).isEqualTo(7L);
            assertThat(saved.getCreditGrade()).isEqualTo("A");
        }

        @Test
        @DisplayName("save 는 createdAt 이 null 인 신규 도메인에도 now() 를 채운다")
        void save_nullCreatedAt_fillsNow() {
            CorporateLoan loan = CorporateLoan.reconstitute(null, "005930", "삼성전자",
                    new BigDecimal("1000000"), new BigDecimal("6600"), BigDecimal.ZERO,
                    30, 82, "A", CorporateLoanStatus.REQUESTED, null);
            when(repository.save(any())).thenReturn(entity(1L));

            adapter().save(loan);

            ArgumentCaptor<CorporateLoanJpaEntity> captor = ArgumentCaptor.forClass(CorporateLoanJpaEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findById present/empty")
        void findById() {
            when(repository.findById(7L)).thenReturn(Optional.of(entity(7L)));
            assertThat(adapter().findById(7L)).isPresent();

            when(repository.findById(8L)).thenReturn(Optional.empty());
            assertThat(adapter().findById(8L)).isEmpty();
        }

        @Test
        @DisplayName("findByStockCode 는 리포지토리 결과를 도메인으로 매핑한다")
        void findByStockCode() {
            when(repository.findByStockCodeOrderByIdDesc("005930")).thenReturn(List.of(entity(2L), entity(1L)));
            List<CorporateLoan> result = adapter().findByStockCode("005930");
            assertThat(result).hasSize(2);
            assertThat(result.getFirst().getStockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("findRecent 는 페이지 요청으로 최신순 조회한다")
        void findRecent() {
            when(repository.findAllByOrderByIdDesc(PageRequest.of(0, 50))).thenReturn(List.of(entity(3L)));
            assertThat(adapter().findRecent(50)).hasSize(1);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class CompanyReputation_ {

        @Mock CompanyReputationRepository repository;

        private CompanyReputationPersistenceAdapter adapter() {
            return new CompanyReputationPersistenceAdapter(repository);
        }

        @Test
        @DisplayName("upsert 는 도메인을 엔티티로 매핑해 저장(merge)한다")
        void upsert() {
            CompanyReputation rep = CompanyReputation.of("005930", 55, "C", "B", LocalDate.of(2026, 7, 7));

            adapter().upsert(rep);

            ArgumentCaptor<CompanyReputationJpaEntity> captor =
                    ArgumentCaptor.forClass(CompanyReputationJpaEntity.class);
            verify(repository).save(captor.capture());
            CompanyReputationJpaEntity e = captor.getValue();
            assertThat(e.getStockCode()).isEqualTo("005930");
            assertThat(e.getScore()).isEqualTo(55);
            assertThat(e.getGrade()).isEqualTo("C");
            assertThat(e.getPreviousGrade()).isEqualTo("B");
            assertThat(e.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 7, 7));
            assertThat(e.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findByStockCode 는 엔티티를 도메인으로 매핑한다")
        void findByStockCode() {
            when(repository.findById("005930")).thenReturn(Optional.of(new CompanyReputationJpaEntity(
                    "005930", 55, "C", "B", LocalDate.of(2026, 7, 7), LocalDateTime.now())));

            Optional<CompanyReputation> result = adapter().findByStockCode("005930");

            assertThat(result).isPresent();
            assertThat(result.get().getScore()).isEqualTo(55);
            assertThat(result.get().getGrade()).isEqualTo("C");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class SellerReputation_ {

        @Mock SellerReputationRepository repository;

        private SellerReputationPersistenceAdapter adapter() {
            return new SellerReputationPersistenceAdapter(repository);
        }

        @Test
        @DisplayName("upsert 는 셀러 평판 엔티티로 매핑해 저장한다")
        void upsert() {
            adapter().upsert(9L, "005930", 70, "B");

            ArgumentCaptor<SellerReputationJpaEntity> captor =
                    ArgumentCaptor.forClass(SellerReputationJpaEntity.class);
            verify(repository).save(captor.capture());
            SellerReputationJpaEntity e = captor.getValue();
            assertThat(e.getSellerId()).isEqualTo(9L);
            assertThat(e.getStockCode()).isEqualTo("005930");
            assertThat(e.getScore()).isEqualTo(70);
            assertThat(e.getGrade()).isEqualTo("B");
            assertThat(e.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findGrade present/empty")
        void findGrade() {
            when(repository.findById(9L)).thenReturn(Optional.of(
                    new SellerReputationJpaEntity(9L, "005930", 70, "B", LocalDateTime.now())));
            assertThat(adapter().findGrade(9L)).contains("B");

            when(repository.findById(10L)).thenReturn(Optional.empty());
            assertThat(adapter().findGrade(10L)).isEmpty();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Loan_ {

        @Mock LoanAdvanceRepository repository;

        private LoanPersistenceAdapter adapter() {
            return new LoanPersistenceAdapter(repository);
        }

        private LoanAdvanceJpaEntity entity(long id) {
            return new LoanAdvanceJpaEntity(id, 7L, new BigDecimal("800000"), new BigDecimal("800"),
                    new BigDecimal("800800"), LoanStatus.DISBURSED, 0, null, null, LocalDateTime.now());
        }

        @Test
        @DisplayName("save 는 도메인↔엔티티 왕복 매핑한다")
        void save() {
            LoanAdvance loan = LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                    new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.DISBURSED);
            when(repository.save(any())).thenReturn(entity(1L));

            LoanAdvance saved = adapter().save(loan);

            assertThat(saved.getId()).isEqualTo(1L);
            assertThat(saved.getSellerId()).isEqualTo(7L);
            assertThat(saved.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        }

        @Test
        @DisplayName("load 존재 시 도메인 반환")
        void load_present() {
            when(repository.findById(1L)).thenReturn(Optional.of(entity(1L)));
            assertThat(adapter().load(1L).getOutstanding()).isEqualByComparingTo("800800");
        }

        @Test
        @DisplayName("load 미존재 시 IllegalArgumentException")
        void load_missing() {
            when(repository.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> adapter().load(99L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("findBySeller 매핑")
        void findBySeller() {
            when(repository.findBySellerIdOrderByIdAsc(7L)).thenReturn(List.of(entity(1L), entity(2L)));
            assertThat(adapter().findBySeller(7L)).hasSize(2);
        }

        @Test
        @DisplayName("findRepayableBySellerForUpdate 는 DISBURSED + OVERDUE 를 함께 조회·매핑한다")
        void findRepayableForUpdate() {
            LoanAdvanceJpaEntity overdue = new LoanAdvanceJpaEntity(2L, 7L, new BigDecimal("800000"),
                    new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.OVERDUE, 0, null, null, LocalDateTime.now());
            when(repository.findBySellerAndStatusesForUpdate(7L,
                    List.of(LoanStatus.DISBURSED, LoanStatus.OVERDUE)))
                    .thenReturn(List.of(entity(1L), overdue));

            assertThat(adapter().findRepayableBySellerForUpdate(7L))
                    .extracting(LoanAdvance::getStatus)
                    .containsExactly(LoanStatus.DISBURSED, LoanStatus.OVERDUE);
        }
    }
}
