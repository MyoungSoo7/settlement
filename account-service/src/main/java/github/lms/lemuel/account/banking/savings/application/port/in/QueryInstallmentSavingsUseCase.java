package github.lms.lemuel.account.banking.savings.application.port.in;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;

import java.util.List;

/**
 * 적금 조회 인바운드 포트.
 *
 * <p>두 메서드 모두 예금주 식별자를 <b>인자로 받는다</b> — 조회 대상을 요청에서 받지 않기 위해서다.
 * 목록 조회에 계약 id 도 사용자 id 도 경로에 없는 것이 IDOR 방어의 본체다.
 */
public interface QueryInstallmentSavingsUseCase {

    /** 단건 조회 — 없으면 404, 남의 계약이면 403. */
    InstallmentSavings get(Long savingsId, String depositorId);

    /** 내 적금 목록 (개설일 내림차순). */
    List<InstallmentSavings> listMine(String depositorId);
}
