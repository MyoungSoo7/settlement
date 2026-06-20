package github.lms.lemuel.pgreconciliation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PG 정산파일 vs 내부 결제 원장 비교 알고리즘 (도메인 순수 로직).
 *
 * <p>입력: PG 파일 행 목록 + 내부 결제 행 목록.
 * 출력: {@link ReconciliationDiscrepancy} 목록 + 매칭된 건수.
 *
 * <p>매칭 키는 {@code pg_transaction_id} (양쪽 공통). 같은 PG 라면 1:1 매칭이 보장된다.
 *
 * <p><b>중요:</b> 이 클래스는 프레임워크 의존성 0 — Spring/JPA 없음. 헥사고날 도메인 영역.
 */
public final class PgReconciliationMatcher {

    /** 1원 미만 차이는 ROUNDING_DIFF (자동 보정 가능). */
    public static final BigDecimal ROUNDING_THRESHOLD = new BigDecimal("1.00");

    private PgReconciliationMatcher() { }

    public static MatchResult match(Long runId,
                                    List<PgTransactionRow> pgRows,
                                    List<InternalPaymentRow> internalRows) {
        // 1) PG 파일 안의 중복 거래 검출 — 같은 transactionId 가 2번 이상 등장하면 DUPLICATE
        Map<String, Integer> pgKeyCount = new HashMap<>();
        for (PgTransactionRow row : pgRows) {
            pgKeyCount.merge(row.pgTransactionId(), 1, Integer::sum);
        }
        Set<String> pgDuplicates = new HashSet<>();
        pgKeyCount.forEach((k, v) -> { if (v > 1) pgDuplicates.add(k); });

        // 2) 키 기반 인덱싱 (중복 키는 첫 번째만 사용 — 추가 발생은 DUPLICATE 로 별도 보고)
        Map<String, PgTransactionRow> pgByKey = new HashMap<>();
        for (PgTransactionRow row : pgRows) {
            pgByKey.putIfAbsent(row.pgTransactionId(), row);
        }
        Map<String, InternalPaymentRow> internalByKey = new HashMap<>();
        for (InternalPaymentRow row : internalRows) {
            internalByKey.put(row.pgTransactionId(), row);
        }

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        int matched = 0;

        // 3) PG 파일 기준 — 양방향 비교의 절반
        for (Map.Entry<String, PgTransactionRow> entry : pgByKey.entrySet()) {
            String pgKey = entry.getKey();
            PgTransactionRow pg = entry.getValue();
            InternalPaymentRow internal = internalByKey.get(pgKey);

            if (pgDuplicates.contains(pgKey)) {
                discrepancies.add(ReconciliationDiscrepancy.newDiscrepancy(
                        runId, DiscrepancyType.DUPLICATE,
                        internal == null ? null : internal.paymentId(),
                        pgKey,
                        internal == null ? null : internal.netAmount(),
                        pg.netAmount()
                ));
                continue;
            }

            if (internal == null) {
                // PG 파일에만 존재 — 내부 거래 누락
                discrepancies.add(ReconciliationDiscrepancy.newDiscrepancy(
                        runId, DiscrepancyType.MISSING_INTERNAL,
                        null, pgKey,
                        null, pg.netAmount()
                ));
                continue;
            }

            BigDecimal diff = pg.netAmount().subtract(internal.netAmount()).abs();
            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                matched++;
            } else if (diff.compareTo(ROUNDING_THRESHOLD) < 0) {
                // 1원 미만 — 반올림 차이로 자동 보정 가능
                discrepancies.add(ReconciliationDiscrepancy.newDiscrepancy(
                        runId, DiscrepancyType.ROUNDING_DIFF,
                        internal.paymentId(), pgKey,
                        internal.netAmount(), pg.netAmount()
                ));
            } else {
                // 1원 이상 — 운영자 검토 필요
                discrepancies.add(ReconciliationDiscrepancy.newDiscrepancy(
                        runId, DiscrepancyType.AMOUNT_MISMATCH,
                        internal.paymentId(), pgKey,
                        internal.netAmount(), pg.netAmount()
                ));
            }
        }

        // 4) 내부에만 있고 PG 파일에 없는 케이스 — MISSING_PG
        for (InternalPaymentRow internal : internalRows) {
            if (!pgByKey.containsKey(internal.pgTransactionId())) {
                discrepancies.add(ReconciliationDiscrepancy.newDiscrepancy(
                        runId, DiscrepancyType.MISSING_PG,
                        internal.paymentId(), internal.pgTransactionId(),
                        internal.netAmount(), null
                ));
            }
        }

        return new MatchResult(matched, discrepancies);
    }

    /** 매칭 결과 — 일치 건수 + 발견된 모든 불일치. */
    public record MatchResult(int matchedCount, List<ReconciliationDiscrepancy> discrepancies) {
        public MatchResult {
            discrepancies = List.copyOf(discrepancies);
        }
    }
}
