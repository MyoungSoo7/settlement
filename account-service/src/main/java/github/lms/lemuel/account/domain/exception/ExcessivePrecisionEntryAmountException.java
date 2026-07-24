package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 전표 금액 정밀도 불변식 위반 — GL 분개 금액의 유효 소수 자릿수가 2(원 단위 통화 표현)를 초과한다.
 *
 * <p>account 는 원천(정산·대출·투자) 금액을 <b>mirror</b> 하는 집계자다. scale&gt;2 금액이 유입되면
 * 공용 {@code Money}(scale 2 HALF_UP)가 <em>조용히 반올림</em>해 저장하므로, GL 과 원천 서브원장 사이에
 * 최대 1원 드리프트가 누적된다(감사 LOW-3). 원천과 어긋나지 않으려면 반올림이 아니라 <b>거부</b>가 정답이다 —
 * 이 예외는 재시도로 복구되지 않는 계약 위반이므로 컨슈머 에러핸들러에서 비재시도(즉시 DLT)로 격리된다.
 *
 * <p>무의미한 후행 0(예: {@code 100.000})은 값이 변하지 않으므로 위반이 아니다 — 유효 소수 자릿수
 * ({@code stripTrailingZeros().scale()})가 2 를 초과할 때만 던진다. 위반 금액을 {@link #getAmount()} 로 보존한다.
 */
public class ExcessivePrecisionEntryAmountException extends AccountDomainException {

    private final transient BigDecimal amount;

    public ExcessivePrecisionEntryAmountException(BigDecimal amount) {
        super(ErrorCode.ENTRY_AMOUNT_SCALE_EXCEEDED, "전표 금액 소수 자릿수 초과(허용 2): " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
