package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.GeneralPayout;

/**
 * 일반지급(GeneralPayout) 저장 포트.
 *
 * <p>멱등 최후 방어는 V10 의 {@code UNIQUE(policy_id, payout_type)} — 같은 계약·유형의
 * 이중 INSERT 는 DB 가 거부한다.
 */
public interface SaveGeneralPayoutPort {

    /** 신규 INSERT — Policy terminal 전이 경로 전용. */
    GeneralPayout insert(GeneralPayout payout);

    /** 상태 전이 반영 — 기존 행 대상. */
    GeneralPayout save(GeneralPayout payout);
}
