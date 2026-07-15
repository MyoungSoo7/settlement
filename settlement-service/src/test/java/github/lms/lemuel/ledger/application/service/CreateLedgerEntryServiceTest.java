package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.exception.LedgerInvariantViolationException;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerStatus;
import github.lms.lemuel.ledger.domain.ReferenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CreateLedgerEntryService 단위 테스트.
 *
 * <p>외부 협력자(LoadSettlementForLedgerPort, LoadLedgerEntryPort, SaveLedgerEntryPort)는
 * 인메모리 fake 로 대체해 도메인 분개 규칙 + 멱등성 + 합계 검증만 격리해 본다.
 */
class CreateLedgerEntryServiceTest {

    private FakeSettlementPort settlements;
    private FakeLedgerPort ledger;
    private CreateLedgerEntryService service;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        settlements = new FakeSettlementPort();
        ledger = new FakeLedgerPort();
        service = new CreateLedgerEntryService(new SingleLedgerEntryWriter(settlements, ledger, ledger));
    }

    @Nested
    class 정산_분개_생성 {

        @Test
        void DONE_정산은_2개_row_생성_차변대변_합계_일치() {
            settlements.put(new SettlementSummary(
                    100L, bd("10000"), bd("300"), bd("9700"), TODAY, "DONE"));

            List<LedgerEntry> rows = service.createFromSettlement(100L);

            assertThat(rows).hasSize(2);

            LedgerEntry first  = rows.get(0);
            LedgerEntry second = rows.get(1);

            assertThat(first.getDebitAccount()).isEqualTo(AccountType.ACCOUNTS_PAYABLE);
            assertThat(first.getCreditAccount()).isEqualTo(AccountType.REVENUE);
            assertThat(first.getAmount()).isEqualByComparingTo("9700.00");
            assertThat(first.getEntryType()).isEqualTo(LedgerEntryType.SETTLEMENT_CONFIRMED);
            assertThat(first.getStatus()).isEqualTo(LedgerStatus.POSTED);

            assertThat(second.getDebitAccount()).isEqualTo(AccountType.COMMISSION_EXPENSE);
            assertThat(second.getCreditAccount()).isEqualTo(AccountType.COMMISSION_REVENUE);
            assertThat(second.getAmount()).isEqualByComparingTo("300.00");

            // 합계 검증
            BigDecimal drSum = rows.stream().map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(drSum).isEqualByComparingTo("10000.00");
        }

        @Test
        void 같은_settlementId_두번_호출하면_두번째는_빈리스트_멱등() {
            settlements.put(new SettlementSummary(
                    101L, bd("10000"), bd("300"), bd("9700"), TODAY, "DONE"));

            List<LedgerEntry> first  = service.createFromSettlement(101L);
            List<LedgerEntry> second = service.createFromSettlement(101L);

            assertThat(first).hasSize(2);
            assertThat(second).isEmpty();
            assertThat(ledger.savedAll()).hasSize(2);
        }

        @Test
        void DONE_가_아니면_예외() {
            settlements.put(new SettlementSummary(
                    102L, bd("10000"), bd("300"), bd("9700"), TODAY, "PROCESSING"));

            assertThatThrownBy(() -> service.createFromSettlement(102L))
                    .isInstanceOf(LedgerInvariantViolationException.class)
                    .hasMessageContaining("DONE");
        }

        @Test
        void settlement_없으면_예외() {
            assertThatThrownBy(() -> service.createFromSettlement(9999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void payment_net_commission_불일치_예외() {
            // 10000 ≠ 9000 + 300
            settlements.put(new SettlementSummary(
                    103L, bd("10000"), bd("300"), bd("9000"), TODAY, "DONE"));

            assertThatThrownBy(() -> service.createFromSettlement(103L))
                    .isInstanceOf(LedgerInvariantViolationException.class)
                    .hasMessageContaining("mismatch");
        }

        @Test
        void commission_0_이면_분개_1개만() {
            settlements.put(new SettlementSummary(
                    104L, bd("10000"), bd("0"), bd("10000"), TODAY, "DONE"));

            List<LedgerEntry> rows = service.createFromSettlement(104L);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getDebitAccount()).isEqualTo(AccountType.ACCOUNTS_PAYABLE);
            assertThat(rows.get(0).getCreditAccount()).isEqualTo(AccountType.REVENUE);
            assertThat(rows.get(0).getAmount()).isEqualByComparingTo("10000.00");
        }

        @Test
        void settlementId_null_이면_예외() {
            assertThatThrownBy(() -> service.createFromSettlement(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class 일괄_처리 {

        @Test
        void 일부_실패는_전체를_막지_않는다() {
            settlements.put(new SettlementSummary(
                    200L, bd("10000"), bd("300"), bd("9700"), TODAY, "DONE"));
            settlements.put(new SettlementSummary(
                    201L, bd("10000"), bd("300"), bd("9700"), TODAY, "PROCESSING")); // 실패할 것
            settlements.put(new SettlementSummary(
                    202L, bd("20000"), bd("600"), bd("19400"), TODAY, "DONE"));

            List<LedgerEntry> rows = service.createFromSettlements(List.of(200L, 201L, 202L));

            // 200 → 2 row, 201 → 0 (실패), 202 → 2 row = 총 4
            assertThat(rows).hasSize(4);
        }

        @Test
        void 빈_리스트는_빈_리스트_반환() {
            assertThat(service.createFromSettlements(List.of())).isEmpty();
            assertThat(service.createFromSettlements(null)).isEmpty();
        }
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    // ========== fakes ==========

    private static class FakeSettlementPort implements LoadSettlementForLedgerPort {
        private final java.util.Map<Long, SettlementSummary> store = new java.util.HashMap<>();

        void put(SettlementSummary s) { store.put(s.id(), s); }

        @Override
        public Optional<SettlementSummary> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    private static class FakeLedgerPort implements SaveLedgerEntryPort, LoadLedgerEntryPort {
        private final List<LedgerEntry> saved = new ArrayList<>();
        private final AtomicLong idSeq = new AtomicLong(1);

        List<LedgerEntry> savedAll() { return List.copyOf(saved); }

        @Override
        public LedgerEntry save(LedgerEntry e) {
            if (e.getId() == null) e.assignId(idSeq.getAndIncrement());
            saved.add(e);
            return e;
        }

        @Override
        public Optional<LedgerEntry> findById(Long id) {
            return saved.stream().filter(e -> id.equals(e.getId())).findFirst();
        }

        @Override
        public boolean existsByReference(Long referenceId, ReferenceType referenceType) {
            return saved.stream().anyMatch(e ->
                    referenceId.equals(e.getReferenceId()) && referenceType == e.getReferenceType());
        }

        @Override
        public List<LedgerEntry> findByReference(Long referenceId, ReferenceType referenceType) {
            return saved.stream().filter(e ->
                    referenceId.equals(e.getReferenceId()) && referenceType == e.getReferenceType())
                    .toList();
        }

        @Override
        public List<LedgerEntry> findBySettlementDateBetween(LocalDate from, LocalDate to) {
            return saved.stream().filter(e ->
                    !e.getSettlementDate().isBefore(from) && !e.getSettlementDate().isAfter(to))
                    .toList();
        }
    }
}
