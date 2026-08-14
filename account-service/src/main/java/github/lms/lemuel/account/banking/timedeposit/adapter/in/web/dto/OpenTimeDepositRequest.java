package github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto;

import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;

import java.math.BigDecimal;

/**
 * 정기예금 개설 요청.
 *
 * <p><b>예금주 식별자 필드는 의도적으로 없다</b> — JWT 주체에서만 파생한다(IDOR 차단).
 * 본문에 {@code depositorId} 를 실어 보내도 바인딩될 필드가 없어 조용히 버려진다.
 *
 * <p>개설일도 받지 않는다 — 서버 시계가 정한다(소급 개설로 이자를 만들어내는 경로 차단).
 *
 * <p>{@code termMonths} 를 원시 {@code int} 가 아닌 {@link Integer} 로 둔 이유: 필드를 누락했을 때
 * 0 으로 조용히 채워지는 대신 null 로 남아, 도메인 검증이 "기간 필수"로 명확히 거절하게 하기 위함이다.
 */
public record OpenTimeDepositRequest(String productName,
                                     BigDecimal principal,
                                     BigDecimal annualRate,
                                     BigDecimal earlyTerminationRate,
                                     Compounding compounding,
                                     Integer termMonths) {

    /** 누락 시 0 — 도메인의 "예치 기간은 1개월 이상" 검증이 받아 거절한다. */
    public int termMonthsOrZero() {
        return termMonths == null ? 0 : termMonths;
    }
}
