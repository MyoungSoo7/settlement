package github.lms.lemuel.account.banking.savings.adapter.in.web.dto;

import github.lms.lemuel.account.banking.savings.domain.SavingsType;

import java.math.BigDecimal;

/**
 * 적금 개설 요청.
 *
 * <p><b>예금주 식별자와 개설일이 없는 것이 이 record 의 핵심이다.</b>
 * <ul>
 *   <li>예금주는 JWT 주체에서만 파생한다 — 본문에 두면 그 자리가 곧 남의 이름으로 계약을 여는 IDOR 경로다.</li>
 *   <li>개설일은 서버 시계(Clock)가 정한다 — 소급 개설은 만기일과 모든 회차의 기일을 통째로 옮겨
 *       없던 이자를 만들어낸다. 이건 자기 계약에 대한 조작이라 소유권 검사로는 막히지 않는다.</li>
 * </ul>
 *
 * <p>값 검증은 Bean Validation 이 아니라 도메인({@code InstallmentSavings.open})이 한다 — 상품
 * 유형에 따라 필수 필드가 뒤바뀌는(FIXED↔FLEXIBLE) 규칙은 애너테이션으로 표현되지 않고,
 * 두 곳에 나눠 쓰면 어느 쪽이 정본인지 흐려진다.
 */
public record OpenInstallmentSavingsRequest(String productName,
                                            SavingsType savingsType,
                                            BigDecimal monthlyAmount,
                                            BigDecimal paymentLimit,
                                            BigDecimal annualRate,
                                            BigDecimal earlyTerminationRate,
                                            int termMonths) {
}
