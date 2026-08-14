package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import java.math.BigDecimal;

/**
 * 퇴직급여 지급 요청 — 잔액이 0 이 되면 계약이 닫힌다.
 *
 * <p>지급일 필드는 없다(서버 {@code Clock}). 수급자 식별자도 없다 — 지급 대상은 언제나 계약의
 * 가입자이며, 이 경로는 ADMIN/MANAGER 전용이라 호출자와 수급자가 서로 다르다.
 */
public record PayBenefitRequest(BigDecimal amount) {
}
