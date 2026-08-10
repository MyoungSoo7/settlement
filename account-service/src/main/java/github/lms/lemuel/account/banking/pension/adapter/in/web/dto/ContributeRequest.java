package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import github.lms.lemuel.account.banking.pension.domain.ContributionSource;

import java.math.BigDecimal;

/**
 * 부담금 납입 요청 — 납입 주체 허용 여부는 제도가 판정한다(DB=사용자만, DC=둘 다, IRP=가입자만).
 *
 * <p>납입일 필드는 없다 — 서버 {@code Clock} 이 정한다.
 */
public record ContributeRequest(BigDecimal amount, ContributionSource source) {
}
