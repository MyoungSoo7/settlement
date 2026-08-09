package github.lms.lemuel.account.banking.savings.adapter.in.web.dto;

import java.math.BigDecimal;

/**
 * 회차 납입 요청. 계약 id 는 경로에서, 예금주는 JWT 에서, <b>납입일은 서버 시계</b>에서 온다 —
 * 본문에는 "몇 회차를 얼마" 만 담긴다.
 *
 * <p>납입일 필드가 없는 것이 이 record 의 두 번째 방어선이다. 납입일을 본문으로 받으면 인증된
 * 사용자가 자기 계약에 과거 날짜를 넣어 예치일수를 늘릴 수 있고, 그건 이자를 임의로 만들어내는
 * 금전 취약점이다 — IDOR 가드로는 막히지 않는다(자기 계약이므로).
 */
public record PayInstallmentRequest(int round, BigDecimal amount) {
}
