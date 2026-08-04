package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.AuthorizationHold;

/**
 * 승인 홀드 저장 포트.
 *
 * <p>{@code authorization_id} 는 UNIQUE 제약이 있어 중복 저장 시 DB 가 최후로 막는다
 * (애플리케이션 멱등 체크({@link LoadAuthorizationHoldPort#findByAuthorizationId}) 가
 * 선방어이고, 이 UNIQUE 제약이 이중 방어다).
 */
public interface SaveAuthorizationHoldPort {

    /** 신규 홀드 저장 또는 상태 변경(voidHold·expire) 후 갱신. */
    AuthorizationHold save(AuthorizationHold hold);
}
