package github.lms.lemuel.account.banking.pension.application.port.in;

import github.lms.lemuel.account.banking.pension.domain.RetirementPension;

import java.util.List;

/**
 * 퇴직연금 조회 인바운드 포트.
 *
 * <p>조회도 {@code subscriberId} 를 JWT 주체에서만 받는다 — 경로에 가입자 식별자를 넣는 순간
 * {@code /pensions/{subscriberId}} 는 그대로 IDOR 경로가 된다.
 */
public interface RetirementPensionQuery {

    /** 본인 계약 단건 — 타인 계약이면 403. */
    RetirementPension get(String subscriberId, Long pensionId);

    /** 본인 계약 전체. */
    List<RetirementPension> listMine(String subscriberId);
}
