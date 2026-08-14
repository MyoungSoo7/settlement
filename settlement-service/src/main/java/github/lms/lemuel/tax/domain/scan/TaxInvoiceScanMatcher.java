package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.TaxInvoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 스캔본 ↔ 발행 세금계산서 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만 한다).
 *
 * <p>후보 탐색 키는 스캔본의 <b>승인번호</b>다. 우리가 발행한 번호는 정산 식별자에서 결정적으로 파생되므로
 * ({@link TaxInvoice#numberFor}), 되읽은 값을 다시 그 규칙으로 만들어 <b>왕복 일치</b>할 때만 정산 식별자로
 * 인정한다 — 번호 형식의 권위는 {@link TaxInvoice} 에 남기고, 타사 계산서 번호로 우연히 매칭되는 것을 막는다.
 *
 * <p><b>IDOR 방어</b>: 후보 발행분의 소유 셀러가 업로더와 다르면 금액이 아무리 같아도 대사하지 않는다
 * (남의 계산서 번호를 적어 올려 타인의 정산 정보를 확인하는 경로 차단).
 */
public final class TaxInvoiceScanMatcher {

    private TaxInvoiceScanMatcher() {
    }

    /**
     * 승인번호에서 정산 식별자를 되읽는다 — 우리 발행번호 형식이 아니면 null.
     */
    public static Long settlementIdFrom(String approvalNumber) {
        if (approvalNumber == null) {
            return null;
        }
        String trimmed = approvalNumber.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.isEmpty() || digits.length() > 18) {
            return null;
        }
        long candidate = Long.parseLong(digits.toString());
        if (candidate <= 0) {
            return null;
        }
        // 왕복 검증: 이 식별자로 만든 발행번호가 원문과 똑같아야 우리 번호다.
        return TaxInvoice.numberFor(candidate).equals(trimmed) ? candidate : null;
    }

    /**
     * 스캔 금액과 발행 금액을 대조해 도달할 상태를 정한다.
     *
     * @param scanSellerId 스캔을 올린 셀러(JWT 주체에서 파생된 값이어야 한다)
     * @param candidate    승인번호로 찾은 발행 세금계산서(없으면 null)
     */
    public static ScanMatchDecision decide(ExtractedTaxInvoice extracted, Long scanSellerId,
                                           TaxInvoice candidate) {
        if (candidate == null) {
            return ScanMatchDecision.unmatched("승인번호에 대응하는 발행 세금계산서를 찾지 못했습니다");
        }
        if (scanSellerId == null || !scanSellerId.equals(candidate.getSellerId())) {
            return ScanMatchDecision.unmatched("발행 세금계산서의 소유 셀러가 업로더와 다릅니다");
        }
        List<String> diffs = new ArrayList<>(3);
        addDiff(diffs, "공급가액", extracted.supplyAmount(), candidate.getSupplyAmount());
        addDiff(diffs, "세액", extracted.taxAmount(), candidate.getTaxAmount());
        addDiff(diffs, "합계금액", extracted.totalAmount(), candidate.getTotalAmount());
        if (diffs.isEmpty()) {
            return ScanMatchDecision.matched(candidate.getId());
        }
        return ScanMatchDecision.mismatched(candidate.getId(), String.join("; ", diffs));
    }

    private static void addDiff(List<String> diffs, String label, BigDecimal scanned, BigDecimal issued) {
        if (issued == null || scanned.compareTo(issued) != 0) {
            diffs.add(label + " 불일치: 스캔 " + scanned.toPlainString()
                    + " vs 발행 " + (issued == null ? "없음" : issued.toPlainString()));
        }
    }
}
