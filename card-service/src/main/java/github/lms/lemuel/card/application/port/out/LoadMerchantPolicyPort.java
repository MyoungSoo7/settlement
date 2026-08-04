package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.MerchantPolicy;

import java.util.Optional;

/**
 * 가맹점/MCC 지출정책 조회 포트.
 *
 * <p>카드 단위 정책이 있으면 계정 단위 정책보다 우선한다(더 구체적인 정책 우선).
 * 정책이 전혀 없으면 "모두 허용"이다 — 정책 미설정은 차단이 아니라 개방이 기본값.
 */
public interface LoadMerchantPolicyPort {

    /**
     * 카드 단위 정책을 우선 조회하고, 없으면 계정 단위 정책을 반환한다.
     * 둘 다 없으면 empty — 호출자는 empty 를 "제한 없음"으로 처리한다.
     *
     * @param cardId          대상 카드 ID
     * @param cardAccountId   카드가 속한 계정 ID
     */
    Optional<MerchantPolicy> findEffectivePolicy(Long cardId, Long cardAccountId);
}
