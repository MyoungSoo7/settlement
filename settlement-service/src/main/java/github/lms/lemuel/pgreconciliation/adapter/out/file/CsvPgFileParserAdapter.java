package github.lms.lemuel.pgreconciliation.adapter.out.file;

import github.lms.lemuel.pgreconciliation.application.port.out.ParsePgFilePort;
import github.lms.lemuel.pgreconciliation.domain.PgTransactionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 단순 CSV 형식 PG 파일 파서.
 *
 * <p>지원 헤더 (첫 줄 필수):
 * <pre>
 * pg_transaction_id,amount,refunded_amount,fee,settled_date
 * TOSS:abc-001,10000,0,300,2026-04-28
 * KCP:tx-002,25000,1000,750,2026-04-28
 * </pre>
 *
 * <p>실 운영 PG (Toss / KCP / NICE / INICIS) 마다 형식이 다르므로 PG 별 어댑터를 별도로
 * 구현해 같은 {@link ParsePgFilePort} 를 만족시킨다 — Strategy 패턴.
 */
@Component
public class CsvPgFileParserAdapter implements ParsePgFilePort {

    private static final Logger log = LoggerFactory.getLogger(CsvPgFileParserAdapter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public List<PgTransactionRow> parse(InputStream input) {
        List<PgTransactionRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("PG 파일이 비어있습니다");
            }
            int[] idx = mapHeader(header);

            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 5) {
                    log.warn("[PgFile] line {} 컬럼 부족, skip: {}", lineNo, line);
                    continue;
                }
                try {
                    rows.add(new PgTransactionRow(
                            cols[idx[0]].trim(),
                            new BigDecimal(cols[idx[1]].trim()),
                            new BigDecimal(cols[idx[2]].trim()),
                            new BigDecimal(cols[idx[3]].trim()),
                            LocalDate.parse(cols[idx[4]].trim(), DATE_FMT)
                    ));
                } catch (RuntimeException ex) {
                    // 한 줄 파싱 실패가 전체 대사를 중단시키지 않도록 — 운영 안정성
                    log.warn("[PgFile] line {} 파싱 실패, skip. line={}, err={}", lineNo, line, ex.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("PG 파일 읽기 실패", e);
        }
        return rows;
    }

    /**
     * 헤더 순서가 PG 마다 달라도 컬럼명만 같으면 파싱 가능하도록 인덱스 매핑.
     */
    private static int[] mapHeader(String header) {
        String[] cols = header.toLowerCase().split(",");
        int pgIdx = -1, amtIdx = -1, refIdx = -1, feeIdx = -1, dateIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            String name = cols[i].trim();
            if (name.equals("pg_transaction_id") || name.equals("transaction_id") || name.equals("tid")) pgIdx = i;
            else if (name.equals("amount")) amtIdx = i;
            else if (name.equals("refunded_amount") || name.equals("refund_amount")) refIdx = i;
            else if (name.equals("fee") || name.equals("commission")) feeIdx = i;
            else if (name.equals("settled_date") || name.equals("settlement_date")) dateIdx = i;
        }
        if (pgIdx < 0 || amtIdx < 0 || refIdx < 0 || feeIdx < 0 || dateIdx < 0) {
            throw new IllegalArgumentException(
                    "필수 헤더 누락 — pg_transaction_id, amount, refunded_amount, fee, settled_date 필요. 받은 헤더: " + header);
        }
        return new int[] { pgIdx, amtIdx, refIdx, feeIdx, dateIdx };
    }
}
