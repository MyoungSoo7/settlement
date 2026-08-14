package github.lms.lemuel.tax.domain.scan;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 세금계산서 스캔 상태 — 전이표가 정본이다(전이는 {@link TaxInvoiceScan} 의 전이 메서드로만).
 *
 * <pre>
 * EXTRACTED  → MATCHED | MISMATCHED | UNMATCHED | REJECTED
 * MISMATCHED → MATCHED | REJECTED        (재대사·반려)
 * UNMATCHED  → MATCHED | REJECTED        (재대사·반려)
 * MATCHED    → (종결)
 * REJECTED   → (종결)
 * </pre>
 *
 * <p>MISMATCHED ↔ UNMATCHED 를 서로 오갈 수 없게 한 것은 의도적이다 — 대사 결과를 번복하는 경로는
 * "다시 대사해서 MATCHED" 또는 "반려" 둘뿐이어야 조사 이력이 남는다.
 */
public enum TaxInvoiceScanStatus {

    /** OCR 추출 완료, 아직 대사 전. */
    EXTRACTED,
    /** 발행 세금계산서와 금액 전부 일치(확정). */
    MATCHED,
    /** 대응 발행분은 찾았으나 금액 불일치. */
    MISMATCHED,
    /** 대응 발행분을 찾지 못함. */
    UNMATCHED,
    /** 관리자 반려(저신뢰·위조 의심 등). */
    REJECTED;

    private static final Map<TaxInvoiceScanStatus, Set<TaxInvoiceScanStatus>> TRANSITIONS;

    static {
        Map<TaxInvoiceScanStatus, Set<TaxInvoiceScanStatus>> map = new EnumMap<>(TaxInvoiceScanStatus.class);
        map.put(EXTRACTED, EnumSet.of(MATCHED, MISMATCHED, UNMATCHED, REJECTED));
        map.put(MISMATCHED, EnumSet.of(MATCHED, REJECTED));
        map.put(UNMATCHED, EnumSet.of(MATCHED, REJECTED));
        map.put(MATCHED, EnumSet.noneOf(TaxInvoiceScanStatus.class));
        map.put(REJECTED, EnumSet.noneOf(TaxInvoiceScanStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public boolean canTransitionTo(TaxInvoiceScanStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    /** 더 이상 어떤 전이도 허용하지 않는 종결 상태인가. */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
