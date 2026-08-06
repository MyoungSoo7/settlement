package github.lms.lemuel.pgreconciliation.adapter.out.file;

import github.lms.lemuel.pgreconciliation.domain.PgTransactionRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 파서의 공제 분해 컬럼 지원 — 선택 컬럼이라 없으면 레거시 동작을 그대로 유지한다.
 */
class CsvPgFileParserFeeColumnsTest {

    private final CsvPgFileParserAdapter parser = new CsvPgFileParserAdapter();

    private List<PgTransactionRow> parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("분해 컬럼이 있으면 항목별로 읽고 실입금 검증이 가능해진다")
    void parsesDecomposedColumns() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,pg_fee_vat,escrow_fee,escrow_vat,transfer_fee,transfer_vat,additional_fee,net_deposit,settled_date
                TOSS:abc-001,10000,0,300,30,100,10,50,5,20,9485,2026-08-05
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.fees().pgFee()).isEqualByComparingTo("300");
        assertThat(row.fees().pgFeeVat()).isEqualByComparingTo("30");
        assertThat(row.fees().escrowFee()).isEqualByComparingTo("100");
        assertThat(row.fees().escrowVat()).isEqualByComparingTo("10");
        assertThat(row.fees().transferFee()).isEqualByComparingTo("50");
        assertThat(row.fees().transferVat()).isEqualByComparingTo("5");
        assertThat(row.fees().additionalFee()).isEqualByComparingTo("20");
        assertThat(row.fees().totalDeduction()).isEqualByComparingTo("515");
        assertThat(row.netDeposit()).isEqualByComparingTo("9485");
        assertThat(row.expectedNetDeposit()).isEqualByComparingTo("9485");
        assertThat(row.hasDepositMismatch()).isFalse();
    }

    @Test
    @DisplayName("분해 컬럼이 없으면 레거시 동작 — fee 만 읽고 실입금 검증은 하지 않는다")
    void legacyHeaderStillWorks() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,settled_date
                TOSS:abc-001,10000,0,300,2026-08-05
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.fees().pgFee()).isEqualByComparingTo("300");
        assertThat(row.fees().isDecomposed()).isFalse();
        assertThat(row.isDepositVerifiable()).isFalse();
        assertThat(row.settledDate()).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("실입금이 계산과 어긋나는 파일도 그대로 읽는다 — 판정은 대사기가 한다")
    void parsesMismatchingDepositWithoutRejecting() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,net_deposit,settled_date
                TOSS:abc-001,10000,0,300,9000,2026-08-05
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.expectedNetDeposit()).isEqualByComparingTo("9700");
        assertThat(row.hasDepositMismatch()).isTrue();
        assertThat(row.depositDifference()).isEqualByComparingTo("-700");
    }

    @Test
    @DisplayName("매입일·지급일 컬럼도 선택 지원")
    void parsesOptionalDates() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,settled_date,purchase_date,payout_date
                TOSS:abc-001,10000,0,300,2026-08-05,2026-08-06,2026-08-10
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.purchaseDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(row.payoutDate()).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    @DisplayName("선택 컬럼이 빈 값이면 0/미신고로 흡수 — PG 가 해당 항목을 안 쓰는 경우")
    void blankOptionalCellsAreAbsorbed() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,pg_fee_vat,net_deposit,settled_date
                TOSS:abc-001,10000,0,300,,,2026-08-05
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.fees().pgFeeVat()).isEqualByComparingTo("0");
        assertThat(row.isDepositVerifiable()).isFalse();
    }

    @Test
    @DisplayName("컬럼 순서가 달라도 헤더명으로 매핑한다")
    void headerOrderIndependent() {
        String csv = """
                settled_date,net_deposit,fee,refunded_amount,amount,pg_transaction_id
                2026-08-05,9700,300,0,10000,TOSS:abc-001
                """;

        PgTransactionRow row = parse(csv).getFirst();

        assertThat(row.pgTransactionId()).isEqualTo("TOSS:abc-001");
        assertThat(row.netDeposit()).isEqualByComparingTo("9700");
        assertThat(row.hasDepositMismatch()).isFalse();
    }
}
