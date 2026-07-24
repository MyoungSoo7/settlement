package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 기간 마감 시 확정 시산표의 차변 합계 ≠ 대변 합계 — 마감 전 균형 불변식 위반.
 *
 * <p>차/대 균형은 각 분개가 구성적으로 보장하므로 이 예외는 데이터 손상(반쪽 전표 등)에 대한
 * 안전망이다. 균형이 깨진 기간은 마감할 수 없다(원인 규명 후 정정 분개 선행).
 * 공통 핸들러가 {@link ErrorCode#LEDGER_PERIOD_IMBALANCE}(422)로 매핑한다.
 */
public class LedgerPeriodImbalanceException extends LedgerDomainException {

    private final transient YearMonth period;
    private final transient BigDecimal totalDebit;
    private final transient BigDecimal totalCredit;

    public LedgerPeriodImbalanceException(YearMonth period, BigDecimal totalDebit, BigDecimal totalCredit) {
        super(ErrorCode.LEDGER_PERIOD_IMBALANCE,
                "기간 " + period + " 시산표 차대 불균형: 차변=" + totalDebit + " 대변=" + totalCredit);
        this.period = period;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public YearMonth getPeriod() {
        return period;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }
}
