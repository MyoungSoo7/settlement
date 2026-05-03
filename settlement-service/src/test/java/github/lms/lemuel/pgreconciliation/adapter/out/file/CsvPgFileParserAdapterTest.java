package github.lms.lemuel.pgreconciliation.adapter.out.file;

import github.lms.lemuel.pgreconciliation.domain.PgTransactionRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvPgFileParserAdapterTest {

    private final CsvPgFileParserAdapter parser = new CsvPgFileParserAdapter();

    @Test
    @DisplayName("표준 헤더 + 데이터 3행 정상 파싱")
    void parse_standardCsv() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,settled_date
                TOSS:abc-1,10000,0,300,2026-04-28
                KCP:tx-2,25000,1000,750,2026-04-28
                NICE:tx-3,7500,500,225,2026-04-28
                """;

        List<PgTransactionRow> rows = parser.parse(stream(csv));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).pgTransactionId()).isEqualTo("TOSS:abc-1");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("10000");
        assertThat(rows.get(0).settledDate()).isEqualTo(LocalDate.of(2026, 4, 28));
        assertThat(rows.get(1).refundedAmount()).isEqualByComparingTo("1000");
        assertThat(rows.get(1).netAmount()).isEqualByComparingTo("24000");
    }

    @Test
    @DisplayName("헤더 컬럼 순서가 달라도 컬럼명으로 인덱스 매핑")
    void parse_reorderedColumns() {
        String csv = """
                amount,fee,pg_transaction_id,settled_date,refunded_amount
                10000,300,TOSS:r1,2026-04-28,0
                """;

        List<PgTransactionRow> rows = parser.parse(stream(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).pgTransactionId()).isEqualTo("TOSS:r1");
        assertThat(rows.get(0).fee()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("동의어 컬럼명(transaction_id, refund_amount, commission, settlement_date) 도 인식")
    void parse_aliasedColumns() {
        String csv = """
                transaction_id,amount,refund_amount,commission,settlement_date
                KCP:alias,5000,0,150,2026-04-28
                """;

        List<PgTransactionRow> rows = parser.parse(stream(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).pgTransactionId()).isEqualTo("KCP:alias");
    }

    @Test
    @DisplayName("필수 컬럼 누락 시 IllegalArgumentException")
    void parse_missingColumn() {
        String csv = """
                pg_transaction_id,amount
                TOSS:1,10000
                """;

        assertThatThrownBy(() -> parser.parse(stream(csv)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 헤더 누락");
    }

    @Test
    @DisplayName("개별 라인 파싱 실패는 skip — 전체 대사를 중단시키지 않음 (운영 안정성)")
    void parse_continuesOnLineError() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,settled_date
                TOSS:ok-1,10000,0,300,2026-04-28
                MALFORMED-LINE-NO-COMMAS
                TOSS:ok-2,20000,0,600,2026-04-28
                INVALID,not-a-number,0,300,2026-04-28
                TOSS:ok-3,5000,0,150,2026-04-28
                """;

        List<PgTransactionRow> rows = parser.parse(stream(csv));

        // 정상 3건만 통과, 잘못된 2건은 로그만 남기고 스킵
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(PgTransactionRow::pgTransactionId)
                .containsExactly("TOSS:ok-1", "TOSS:ok-2", "TOSS:ok-3");
    }

    @Test
    @DisplayName("빈 라인은 무시")
    void parse_blankLines() {
        String csv = """
                pg_transaction_id,amount,refunded_amount,fee,settled_date
                TOSS:1,10000,0,300,2026-04-28

                TOSS:2,20000,0,600,2026-04-28
                """;

        List<PgTransactionRow> rows = parser.parse(stream(csv));

        assertThat(rows).hasSize(2);
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
