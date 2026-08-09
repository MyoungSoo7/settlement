package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;

import java.math.BigDecimal;

/**
 * 중도인출 요청 — 사유는 법정 6종 enum 이라 임의 사유가 표현 불가능하다.
 * (DB형은 사유와 무관하게 제도 자체가 인출을 막는다.)
 *
 * <p>인출일 필드는 없다 — 서버 {@code Clock} 이 정한다.
 */
public record MidWithdrawalRequest(BigDecimal amount, MidWithdrawalReason reason) {
}
