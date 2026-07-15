package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReverseEntryServiceTest {

    private FakeSettlementPort settlements;
    private FakeLedgerPort ledger;
    private ReverseEntryService service;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        settlements = new FakeSettlementPort();
        ledger = new FakeLedgerPort();
        service = new ReverseEntryService(settlements, ledger, ledger);

        // 표준 settlement: 결제 10,000 / 수수료 300 (3%) / net 9,700
        settlements.put(new SettlementSummary(
                1L, bd("10000"), bd("300"), bd("9700"), TODAY, "DONE"));
    }

    @Nested
    class 환불_역분개 {

        @Test
        void 전액환불_2개_row_생성_합계는_환불금액과_일치() {
            List<LedgerEntry> rows = service.reverseForRefund(1L, 100L, bd("10000"), TODAY);

            assertThat(rows).hasSize(2);

            LedgerEntry netRow = rows.get(0);
            LedgerEntry feeRow = rows.get(1);

            assertThat(netRow.getDebitAccount()).isEqualTo(AccountType.SALES_REFUND);
            assertThat(netRow.getCreditAccount()).isEqualTo(AccountType.ACCOUNTS_PAYABLE);
            assertThat(netRow.getAmount()).isEqualByComparingTo("9700.00");
            assertThat(netRow.getEntryType()).isEqualTo(LedgerEntryType.REFUND_REVERSED);
            assertThat(netRow.getReferenceType()).isEqualTo(ReferenceType.REFUND);
            assertThat(netRow.getReferenceId()).isEqualTo(100L);
            assertThat(netRow.getStatus()).isEqualTo(LedgerStatus.POSTED);

            assertThat(feeRow.getDebitAccount()).isEqualTo(AccountType.SALES_REFUND);
            assertThat(feeRow.getCreditAccount()).isEqualTo(AccountType.COMMISSION_REVENUE);
            assertThat(feeRow.getAmount()).isEqualByComparingTo("300.00");

            BigDecimal sum = rows.stream().map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("10000.00");
        }

        @Test
        void 부분환불_비율로_분해_합계_일치() {
            // 5,000 환불 → commission rate 3% 기준
            //   refundedCommission = 5000 × 300/10000 = 150.00
            //   refundedNet        = 5000 − 150       = 4850.00
            List<LedgerEntry> rows = service.reverseForRefund(1L, 101L, bd("5000"), TODAY);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getAmount()).isEqualByComparingTo("4850.00");
            assertThat(rows.get(1).getAmount()).isEqualByComparingTo("150.00");

            BigDecimal sum = rows.stream().map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("5000.00");
        }

        @Test
        void 같은_refundId_두번_호출하면_두번째는_빈리스트_멱등() {
            List<LedgerEntry> first  = service.reverseForRefund(1L, 102L, bd("1000"), TODAY);
            List<LedgerEntry> second = service.reverseForRefund(1L, 102L, bd("1000"), TODAY);

            assertThat(first).hasSize(2);
            assertThat(second).isEmpty();
            assertThat(ledger.savedAll()).hasSize(2);
        }

        @Test
        void refundAmount_가_paymentAmount_초과시_거부() {
            assertThatThrownBy(() -> service.reverseForRefund(1L, 103L, bd("99999"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds");
        }

        @Test
        void settlement_없으면_거부() {
            assertThatThrownBy(() -> service.reverseForRefund(9999L, 104L, bd("100"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void 필수필드_검증() {
            assertThatThrownBy(() -> service.reverseForRefund(null, 105L, bd("100"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.reverseForRefund(1L, null, bd("100"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.reverseForRefund(1L, 105L, null, TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.reverseForRefund(1L, 105L, bd("0"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.reverseForRefund(1L, 105L, bd("100"), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void commission_0_settlement_의_환불은_SALES_REFUND_ACCOUNTS_PAYABLE_단일_row() {
            settlements.put(new SettlementSummary(
                    2L, bd("10000"), bd("0"), bd("10000"), TODAY, "DONE"));

            List<LedgerEntry> rows = service.reverseForRefund(2L, 200L, bd("3000"), TODAY);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getCreditAccount()).isEqualTo(AccountType.ACCOUNTS_PAYABLE);
            assertThat(rows.get(0).getAmount()).isEqualByComparingTo("3000.00");
        }
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    // ========== fakes (CreateLedgerEntryServiceTest 와 동일 구조) ==========

    private static class FakeSettlementPort implements LoadSettlementForLedgerPort {
        private final Map<Long, SettlementSummary> store = new HashMap<>();
        void put(SettlementSummary s) { store.put(s.id(), s); }
        @Override public Optional<SettlementSummary> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    private static class FakeLedgerPort implements SaveLedgerEntryPort, LoadLedgerEntryPort {
        private final List<LedgerEntry> saved = new ArrayList<>();
        private final AtomicLong idSeq = new AtomicLong(1);
        List<LedgerEntry> savedAll() { return List.copyOf(saved); }

        @Override public LedgerEntry save(LedgerEntry e) {
            if (e.getId() == null) e.assignId(idSeq.getAndIncrement());
            saved.add(e);
            return e;
        }
        @Override public Optional<LedgerEntry> findById(Long id) {
            return saved.stream().filter(e -> id.equals(e.getId())).findFirst();
        }
        @Override public boolean existsByReference(Long referenceId, ReferenceType referenceType) {
            return saved.stream().anyMatch(e ->
                    referenceId.equals(e.getReferenceId()) && referenceType == e.getReferenceType());
        }
        @Override public List<LedgerEntry> findByReference(Long referenceId, ReferenceType referenceType) {
            return saved.stream().filter(e ->
                    referenceId.equals(e.getReferenceId()) && referenceType == e.getReferenceType())
                    .toList();
        }
        @Override public List<LedgerEntry> findBySettlementDateBetween(LocalDate from, LocalDate to) {
            return saved.stream().filter(e ->
                    !e.getSettlementDate().isBefore(from) && !e.getSettlementDate().isAfter(to))
                    .toList();
        }
    }
}
