package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import github.lms.lemuel.account.banking.pension.domain.PensionScheme;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 퇴직연금 가입 요청.
 *
 * <p><b>가입자 식별자 필드는 의도적으로 없다</b> — JWT 주체에서만 파생한다(IDOR 차단).
 * <b>개설일 필드도 없다</b> — 서버 {@code Clock} 이 정한다(소급 개설 차단).
 *
 * @param employerName DB·DC 는 필수, IRP 는 비워야 한다
 * @param birthDate    생년월일 — 수급 개시 연령(만 55세) 판정의 근거. 가입 때 한 번만 받고 이후
 *                     수급 신청에서는 받지 않는다. 자기신고 값이라는 한계는
 *                     {@code RetirementPension#startBenefit} 문서에 명시돼 있다.
 * @param annualRate   계약 기준 운용이율 — {@code [0,1)} 소수(연 3.5% = 0.035)
 */
public record OpenPensionRequest(PensionScheme scheme, String employerName, LocalDate birthDate,
                                 BigDecimal annualRate, String productName, BigDecimal productRate) {
}
