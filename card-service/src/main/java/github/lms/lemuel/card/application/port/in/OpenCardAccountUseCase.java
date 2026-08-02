package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.CardAccount;

/**
 * 셀러 법인의 카드계정 개설(심사) 인바운드 포트.
 *
 * <p>조직당 1개만 개설할 수 있고, 개설 시점에 재원(정산 미지급금 + 홀드백)과 평판등급으로
 * 마스터 한도를 산정한다. 심사 탈락도 <b>근거와 함께</b> 기록으로 남는다 —
 * 다만 재원을 조회하지 못한 경우는 예외다(그건 탈락이 아니라 판단 불가라 아무 기록도 남기지 않는다).
 */
public interface OpenCardAccountUseCase {

    CardAccount open(OpenCardAccountCommand command);

    /**
     * @param organizationId  카드계정을 개설할 조직
     * @param requesterUserId <b>JWT 주체에서 파생한</b> 요청자 — 요청 본문에서 받지 않는다.
     *                        본문의 사용자 식별자를 믿으면 그 자체가 권한 상승 경로다(IDOR).
     */
    record OpenCardAccountCommand(Long organizationId, Long requesterUserId) {
    }
}
