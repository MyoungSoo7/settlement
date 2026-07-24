package github.lms.lemuel.tax.domain;

import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 세무 3자 대사(Seed B2 핵심) — 세무 계산 ↔ 세금계산서 ↔ 세무 원장 전표 ↔ <b>실제 payout 감액분</b>이
 * 정합하는지 구성적으로 검증한다.
 *
 * <p>ADR 0029 대사 항등식(2026-07-24 정정):
 * <pre>
 * commission           = 세금계산서 합계
 * commission − vat     = 세금계산서 공급가액 = 세무계산 공급가액
 * vatAmount             = 세금계산서 세액     = 원장 VAT_PAYABLE 예수 합
 * withholdingAmount     = immediatePayoutAmount − 실제 IMMEDIATE payout 금액   (★ 실효 교차검증, 아래 참고)
 * 개인 실지급 = netAmount − withholdingAmount
 * 세무 전표는 차대 균형(각 row Dr≠Cr) · POSTED 불변
 * </pre>
 *
 * <p><b>2026-07-24 정정 — 대사 실효화(독립 GL 감사 지적)</b>: 과거 버전은 원천징수를 settlement 자체
 * 원장(WITHHOLDING_PAYABLE)의 예수 합과만 비교했는데, 그 값도 같은 {@link TaxCalculation} 에서 파생돼
 * <b>자기참조</b>였다(계산이 틀리면 대사도 똑같이 틀려서 통과) — 실제 현금이 얼마나 나갔는지는 전혀 보지
 * 않았다. 이제는 settlement 자신의 <b>독립적인 사실</b>인 실제 IMMEDIATE {@code Payout} 금액을 대조한다.
 * {@code (settlement 확정 시점의 immediatePayoutAmount) − (실제 지급된 금액) = 실제 감액분} 을 세무 계산의
 * withholdingAmount 와 비교해, "장부는 원천징수를 계산했는데 실제 송금은 전액 나간" HIGH #4 류 결함을
 * 원장이 아니라 <b>지급 사실</b>로 검출한다.
 *
 * <p><b>범위 한계(문서화된 한계)</b>: 이 교차검증은 회수채권 상계(offset)를 분리하지 못한다 — 실제 감액분
 * 은 {@code offset + withholding} 합산이므로, 같은 정산에서 회수채권 상계가 동시에 발생하면 이 축이 허위
 * 불일치를 표시할 수 있다(상계 조회 포트가 세무 패키지에 없어 이번 Seed 범위에서는 무시한다 — 후속 과제).
 * 상계가 없는(0) 일반적인 경우에는 정확히 원천징수만 검출한다. payout 이 아직 생성되지 않은 경우
 * ({@code actualImmediatePayoutAmount == null}) 이 축은 평가하지 않는다(판단 근거 부재 — 억지 실패 금지).
 *
 * <p>불변식은 도메인이 소유한다 — 서비스는 자료(계산·계산서·원장·실제 payout 금액)를 넘기기만 하면
 * 이 팩토리가 대사 결과를 낸다.
 */
public final class TaxReconciliation {

    private final List<TaxReconciliationCheck> checks;
    private final BigDecimal ledgerVatAccrued;
    private final BigDecimal actualWithholdingDeducted; // null = 미평가(payout 미존재)
    private final boolean ledgerBalanced;
    private final boolean matched;

    private TaxReconciliation(List<TaxReconciliationCheck> checks, BigDecimal ledgerVatAccrued,
                              BigDecimal actualWithholdingDeducted, boolean ledgerBalanced, boolean matched) {
        this.checks = List.copyOf(checks);
        this.ledgerVatAccrued = ledgerVatAccrued;
        this.actualWithholdingDeducted = actualWithholdingDeducted;
        this.ledgerBalanced = ledgerBalanced;
        this.matched = matched;
    }

    /**
     * 계산·세금계산서·세무원장 전표·실제 payout 금액으로 3자(+실효) 대사를 수행한다.
     *
     * @param taxEntries               {@code (settlementId, SETTLEMENT_TAX)} 로 조회한 세무 전표(POSTED). null 은 빈 목록.
     * @param immediatePayoutAmount    정산 확정 시점의 즉시지급 산정액(net − 미해제 holdback). null 이면 이 교차검증 skip.
     * @param actualImmediatePayoutAmount 실제 생성된 IMMEDIATE Payout 의 금액. null 이면 아직 지급 전 — 이 교차검증 skip.
     */
    public static TaxReconciliation reconcile(TaxCalculation calc, TaxInvoice invoice,
                                              List<LedgerEntry> taxEntries,
                                              BigDecimal immediatePayoutAmount,
                                              BigDecimal actualImmediatePayoutAmount) {
        if (calc == null) {
            throw new TaxInvariantViolationException("TaxCalculation 은 필수입니다");
        }
        if (invoice == null) {
            throw new TaxInvariantViolationException("TaxInvoice 는 필수입니다");
        }
        List<LedgerEntry> entries = taxEntries == null ? List.of() : taxEntries;

        BigDecimal ledgerVat = sumCredits(entries, AccountType.VAT_PAYABLE);
        boolean balanced = allPostedAndBalanced(entries);

        List<TaxReconciliationCheck> checks = new ArrayList<>();
        // 세금계산서 합계 = commission(포함과세 항등식의 최종 검증점).
        checks.add(TaxReconciliationCheck.of("세금계산서합계=commission", calc.commission(), invoice.getTotalAmount()));
        // 공급가액: (commission−vat) = 세금계산서 공급가액 = 세무계산 공급가액.
        checks.add(TaxReconciliationCheck.of("공급가액=세금계산서공급가액", calc.supplyAmount(), invoice.getSupplyAmount()));
        // 부가세: 계산 = 세금계산서 세액.
        checks.add(TaxReconciliationCheck.of("부가세=세금계산서세액", calc.vatAmount(), invoice.getTaxAmount()));
        // 부가세: 계산 = 원장 VAT_PAYABLE 예수 합.
        checks.add(TaxReconciliationCheck.of("부가세=원장VAT예수", calc.vatAmount(), ledgerVat));
        // 실지급: net − withholding.
        checks.add(TaxReconciliationCheck.of("실지급=순정산−원천징수",
                calc.netAmount().subtract(calc.withholdingAmount()), calc.netPayable()));
        // 세금계산서 공급가액+세액 = 합계(자기 정합성).
        checks.add(TaxReconciliationCheck.of("세금계산서공급가액+세액=합계",
                invoice.getSupplyAmount().add(invoice.getTaxAmount()), invoice.getTotalAmount()));

        BigDecimal actualWithholdingDeducted = null;
        if (immediatePayoutAmount != null && actualImmediatePayoutAmount != null) {
            // ★ 자기참조 탈피 — 세무 계산이 아니라 실제 지급 사실(Payout.amount)과 대조한다.
            actualWithholdingDeducted = immediatePayoutAmount.subtract(actualImmediatePayoutAmount);
            checks.add(TaxReconciliationCheck.of("원천징수=실제payout감액분",
                    calc.withholdingAmount(), actualWithholdingDeducted));
        }

        boolean allChecksPass = checks.stream().allMatch(TaxReconciliationCheck::passed);
        boolean matched = allChecksPass && balanced;

        return new TaxReconciliation(checks, ledgerVat, actualWithholdingDeducted, balanced, matched);
    }

    private static BigDecimal sumCredits(List<LedgerEntry> entries, AccountType creditAccount) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (e.getCreditAccount() == creditAccount && !e.isReversed()) {
                sum = sum.add(e.getAmount());
            }
        }
        return sum;
    }

    private static boolean allPostedAndBalanced(List<LedgerEntry> entries) {
        for (LedgerEntry e : entries) {
            // 세무 전표는 확정 전기(POSTED)이며 차변≠대변(구성적 균형). 어느 하나라도 어긋나면 대사 실패.
            if (!e.isPosted() || e.getDebitAccount() == e.getCreditAccount()) {
                return false;
            }
        }
        return true;
    }

    public List<TaxReconciliationCheck> checks() {
        return checks;
    }

    public List<TaxReconciliationCheck> mismatches() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }

    public boolean matched() {
        return matched;
    }

    public boolean ledgerBalanced() {
        return ledgerBalanced;
    }

    public BigDecimal ledgerVatAccrued() {
        return ledgerVatAccrued;
    }

    /**
     * 실제 payout 금액에서 역산한 원천징수 감액분(offset 이 없으면 withholdingAmount 와 정확히 일치해야
     * 한다). payout 미존재 시 null(그 교차검증을 평가하지 않았다는 뜻).
     */
    public BigDecimal actualWithholdingDeducted() {
        return actualWithholdingDeducted;
    }
}
