package github.lms.lemuel.pgreconciliation.adapter.out.file;

import github.lms.lemuel.pgreconciliation.application.port.out.ParsePgFilePort;
import github.lms.lemuel.pgreconciliation.domain.PgFeeBreakdown;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 단순 CSV 형식 PG 파일 파서.
 *
 * <p><b>필수 헤더</b>: {@code pg_transaction_id, amount, refunded_amount, fee, settled_date}
 *
 * <p><b>선택 헤더</b>(공제 분해·실입금 검증용) — 없으면 레거시 동작 그대로:
 * {@code pg_fee_vat, escrow_fee, escrow_vat, transfer_fee, transfer_vat, additional_fee,
 * net_deposit, purchase_date, payout_date}
 *
 * <pre>
 * pg_transaction_id,amount,refunded_amount,fee,pg_fee_vat,net_deposit,settled_date
 * TOSS:abc-001,10000,0,300,30,9670,2026-08-05
 * </pre>
 *
 * <p>{@code net_deposit} 이 있으면 도메인이 {@code 매출 − 환불 − 공제 = 실입금} 을 검증한다.
 * 없으면 검증 불가로 남고, 그 사실이 불일치로 둔갑하지 않는다.
 *
 * <p>실 운영 PG (Toss / KCP / NICE / INICIS) 마다 형식이 다르므로 PG 별 어댑터를 별도로
 * 구현해 같은 {@link ParsePgFilePort} 를 만족시킨다 — Strategy 패턴.
 */
@Component
public class CsvPgFileParserAdapter implements ParsePgFilePort {

    private static final Logger log = LoggerFactory.getLogger(CsvPgFileParserAdapter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 필수 컬럼 — 하나라도 없으면 파일을 거부한다. */
    private static final List<String> REQUIRED = List.of(
            "pg_transaction_id", "amount", "refunded_amount", "fee", "settled_date");

    /** 헤더 별칭 — PG 마다 컬럼명이 달라도 같은 의미로 매핑한다. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("transaction_id", "pg_transaction_id"),
            Map.entry("tid", "pg_transaction_id"),
            Map.entry("refund_amount", "refunded_amount"),
            Map.entry("commission", "fee"),
            Map.entry("pg_charge", "fee"),
            Map.entry("settlement_date", "settled_date"),
            Map.entry("pg_surtax", "pg_fee_vat"),
            Map.entry("escrow_charge", "escrow_fee"),
            Map.entry("escrow_surtax", "escrow_vat"),
            Map.entry("trans_charge", "transfer_fee"),
            Map.entry("trans_surtax", "transfer_vat"),
            Map.entry("add_charge", "additional_fee"),
            Map.entry("deposit_amount", "net_deposit"),
            Map.entry("give_day", "payout_date"));

    @Override
    public List<PgTransactionRow> parse(InputStream input) {
        List<PgTransactionRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("PG 파일이 비어있습니다");
            }
            Map<String, Integer> idx = mapHeader(header);

            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                try {
                    rows.add(toRow(cols, idx));
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

    private static PgTransactionRow toRow(String[] cols, Map<String, Integer> idx) {
        PgFeeBreakdown fees = PgFeeBreakdown.of(
                money(cols, idx, "fee"),
                money(cols, idx, "pg_fee_vat"),
                money(cols, idx, "escrow_fee"),
                money(cols, idx, "escrow_vat"),
                money(cols, idx, "transfer_fee"),
                money(cols, idx, "transfer_vat"),
                money(cols, idx, "additional_fee"));

        return PgTransactionRow.of(
                required(cols, idx, "pg_transaction_id"),
                money(cols, idx, "amount"),
                money(cols, idx, "refunded_amount"),
                fees,
                money(cols, idx, "net_deposit"),   // null = 미신고 → 실입금 검증 대상 아님
                date(cols, idx, "settled_date"),
                date(cols, idx, "purchase_date"),
                date(cols, idx, "payout_date"));
    }

    /** 값이 없거나 빈 칸이면 null — 금액은 도메인이 0 으로, 실입금은 '미신고'로 해석한다. */
    private static String cell(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i >= cols.length) return null;
        String v = cols[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static String required(String[] cols, Map<String, Integer> idx, String name) {
        String v = cell(cols, idx, name);
        if (v == null) throw new IllegalArgumentException(name + " 값이 비어있습니다");
        return v;
    }

    /** 금액은 문자열에서 직접 BigDecimal 로 — double 경유 금지(정밀도 손실). */
    private static BigDecimal money(String[] cols, Map<String, Integer> idx, String name) {
        String v = cell(cols, idx, name);
        return v == null ? null : new BigDecimal(v);
    }

    private static LocalDate date(String[] cols, Map<String, Integer> idx, String name) {
        String v = cell(cols, idx, name);
        return v == null ? null : LocalDate.parse(v, DATE_FMT);
    }

    /**
     * 헤더명 → 컬럼 인덱스 매핑. 순서가 PG 마다 달라도 이름만 맞으면 파싱된다.
     * 별칭({@link #ALIASES})으로 PG 별 표기 차이를 흡수한다.
     */
    private static Map<String, Integer> mapHeader(String header) {
        String[] cols = header.toLowerCase().split(",");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            String name = cols[i].trim();
            idx.putIfAbsent(ALIASES.getOrDefault(name, name), i);
        }
        List<String> missing = REQUIRED.stream().filter(r -> !idx.containsKey(r)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "필수 헤더 누락 — " + missing + " 필요. 받은 헤더: " + header);
        }
        return idx;
    }
}
