package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;

/**
 * 수급 개시 요청 — 수급 형태 하나만 받는다.
 *
 * <p><b>만 나이·가입기간·개시일 필드가 없는 것이 이 DTO 의 요점이다.</b> 셋 다 서버가 정한다 —
 * 나이는 가입 시 기록한 생년월일, 가입기간은 개설일, 개시일은 {@code Clock} 에서 파생한다.
 * 이 값들을 요청에서 받으면 아무나 {@code age=60, subscribedYears=20} 을 보내 법정 요건
 * (만 55세·가입 10년)을 그대로 통과시킬 수 있어 요건 검사 전체가 장식이 된다.
 */
public record StartBenefitRequest(BenefitType benefitType) {
}
