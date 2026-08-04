package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.AuthorizationHold;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 승인 홀드 조회 포트.
 *
 * <p>가용한도 계산과 일·월 지출 집계를 위한 조회 메서드를 제공한다.
 * 비관적 락({@link LoadCardAccountPort#findByIdForUpdate})을 잡은 뒤에 집계를 호출해야
 * 동시 승인 경합에서 가용한도 불변식이 깨지지 않는다.
 */
public interface LoadAuthorizationHoldPort {

    /**
     * 멱등 자연키({@code authorizationId})로 홀드 조회.
     * 재전송·리플레이 감지 및 중복 승인 방어에 쓴다.
     */
    Optional<AuthorizationHold> findByAuthorizationId(String authorizationId);

    /**
     * 비관적 락({@code PESSIMISTIC_WRITE})으로 홀드 조회.
     * 매입·취소·환불 시 동시 상태 변경 경합을 방어한다.
     */
    Optional<AuthorizationHold> findByAuthorizationIdForUpdate(String authorizationId);

    /**
     * 카드계정의 ACTIVE 홀드 합계 — master 가용한도 계산용.
     * 비관적 락 획득 후 호출해야 정합성이 보장된다.
     */
    BigDecimal sumActiveHoldsByAccount(Long cardAccountId);

    /**
     * 특정 카드의 ACTIVE 홀드 합계 — 서브한도 가용한도 계산용.
     */
    BigDecimal sumActiveHoldsByCard(Long cardId);

    /**
     * 특정 카드의 특정 날 지출 합계(ACTIVE + CAPTURED).
     * 가맹점 정책의 일 한도 평가에 쓴다.
     */
    BigDecimal sumHoldsByCardAndDate(Long cardId, LocalDate date);

    /**
     * 특정 카드의 특정 월 지출 합계(ACTIVE + CAPTURED).
     * 가맹점 정책의 월 한도 평가에 쓴다.
     */
    BigDecimal sumHoldsByCardAndMonth(Long cardId, YearMonth month);

    /**
     * 특정 시각 이전에 생성된 ACTIVE 홀드 목록 — 미매입 만료 배치 전용.
     *
     * @param before 이 시각보다 이전에 {@code authorized_at} 된 ACTIVE 홀드
     * @return 만료 대상 홀드 목록
     */
    List<AuthorizationHold> findAllActiveAuthorizedBefore(Instant before);
}
