package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase.PrepayResult;

import java.math.BigDecimal;

/**
 * 중도상환 응답 — 실제 차감액과 수수료를 대출 상태와 함께 돌려준다.
 *
 * <p><b>수수료는 상환액에 부가된다(fee-on-top)</b> — 요청 {@code amount} 는 전액 원금 차감에 쓰이고,
 * 수수료는 그 위에 별도 수취된다(총 수취액 = prepaidAmount + earlyRepaymentFee). 요청액을 원금과
 * 수수료로 쪼개는 방식이 아니다. 수수료는 서버가 산정하므로(잔존기간 비례) 응답에 명시해야
 * 차주가 총 수취액을 재현할 수 있고 CS 분쟁에서 근거가 된다.
 */
public record SecuredLoanPrepayResponse(
        BigDecimal prepaidAmount,
        BigDecimal earlyRepaymentFee,
        SecuredLoanResponse loan) {

    public static SecuredLoanPrepayResponse from(PrepayResult result) {
        return new SecuredLoanPrepayResponse(result.prepaidAmount(), result.fee(),
                SecuredLoanResponse.from(result.loan()));
    }
}
