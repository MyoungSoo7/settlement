package github.lms.lemuel.tax.domain;

import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 세무 전표 도메인 팩토리 — 정산 1건의 세무 계산({@link TaxCalculation})을 settlement 자체원장의
 * <b>차1·대1 균형 분개</b>로 전개한다(ADR 0029, {@code LedgerEntry.balancedPairForSettlement} 와 동형).
 *
 * <pre>
 * VAT (수수료>0, 포함과세) : Dr COMMISSION_REVENUE / Cr VAT_PAYABLE = vatAmount
 * </pre>
 *
 * <p><b>2026-07-24 정정</b>: 부가세는 <b>포함과세</b>로 바뀌어, 정산 확정 시 이미 인식된
 * {@code COMMISSION_REVENUE}(부가세 포함 수수료 전액) 중 부가세 부분을 예수부채로 갈라낸다
 * (별도 미수금(AR) 청구가 아니다 — 외부과세 모델의 AR 무한적재 결함을 제거).
 *
 * <p><b>원천징수는 이 원장에 전기하지 않는다</b>. 원천징수는 실제 payout 지급액에서 공제되며
 * (settlement 확정 배치가 즉시지급액을 {@code net−holdback−offset−withholding} 으로 산정), 그 결과
 * 남는 SELLER_PAYABLE 잔여를 닫는 {@code Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE} 전표는
 * <b>account-service GL</b>(ADR 0026 폐루프의 확장)에 전기된다 — settlement 는
 * {@code lemuel.settlement.withholding_accrued} 를 발행할 뿐, 자신의 원장에는 원천징수 계정을 두지 않는다.
 *
 * <p>row 는 전기(POSTED)되어 반환된다. 부가세가 0(수수료 1원 미만 절사 등)이면 분개가 성립하지 않으므로
 * 빈 목록을 반환한다.
 */
public final class TaxJournal {

    private TaxJournal() {
    }

    /**
     * 세무 계산 → 전기된 균형 분개 목록(최대 1 row, VAT 전용). reference 는
     * {@code (settlementId, SETTLEMENT_TAX)} 로 고정한다.
     *
     * @throws TaxInvariantViolationException settlementId·계산·정산일이 유효하지 않은 경우
     */
    public static List<LedgerEntry> postingsFor(Long settlementId, TaxCalculation calc,
                                                LocalDate settlementDate) {
        if (settlementId == null || settlementId <= 0) {
            throw new TaxInvariantViolationException("settlementId 는 양수여야 합니다: " + settlementId);
        }
        if (calc == null) {
            throw new TaxInvariantViolationException("TaxCalculation 은 필수입니다");
        }
        if (settlementDate == null) {
            throw new TaxInvariantViolationException("settlementDate 는 필수입니다");
        }

        List<LedgerEntry> entries = new ArrayList<>(1);

        if (calc.hasVat()) {
            LedgerEntry vat = LedgerEntry.of(
                    settlementId, ReferenceType.SETTLEMENT_TAX, LedgerEntryType.VAT_ACCRUED,
                    AccountType.COMMISSION_REVENUE, AccountType.VAT_PAYABLE,
                    calc.vatAmount(), settlementDate, "세무 — 수수료 부가세 예수(포함과세)");
            vat.post();
            entries.add(vat);
        }

        return entries;
    }
}
