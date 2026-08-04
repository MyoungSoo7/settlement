package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ExpireStaleHoldsUseCase;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 미매입 홀드 만료 배치 구현.
 *
 * <p>지정된 일 수 이상 ACTIVE 로 남아 있는 홀드를 EXPIRED 로 전환해 가용한도를 복구한다.
 *
 * <p>각 홀드를 개별로 갱신한다(배치 단위 트랜잭션 — 한 홀드 실패가 전체를 롤백하지 않도록
 * 호출자({@code HoldExpiryScheduler})에서 예외를 삼킨 뒤 로그로만 남긴다.
 */
@Service
public class ExpireStaleHoldsService implements ExpireStaleHoldsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireStaleHoldsService.class);

    private final LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    private final SaveAuthorizationHoldPort saveAuthorizationHoldPort;

    public ExpireStaleHoldsService(LoadAuthorizationHoldPort loadAuthorizationHoldPort,
                                    SaveAuthorizationHoldPort saveAuthorizationHoldPort) {
        this.loadAuthorizationHoldPort = loadAuthorizationHoldPort;
        this.saveAuthorizationHoldPort = saveAuthorizationHoldPort;
    }

    @Override
    @Transactional
    public int expireStaleHolds(int expiryDays) {
        Instant threshold = Instant.now().minus(expiryDays, ChronoUnit.DAYS);
        List<AuthorizationHold> stale = loadAuthorizationHoldPort.findAllActiveAuthorizedBefore(threshold);

        int expired = 0;
        for (AuthorizationHold hold : stale) {
            try {
                hold.expire();
                saveAuthorizationHoldPort.save(hold);
                expired++;
                log.debug("[HoldExpiry] 만료 처리 authorizationId={}", hold.getAuthorizationId());
            } catch (RuntimeException e) {
                log.error("[HoldExpiry] 홀드 만료 실패(건너뜀) authorizationId={} error={}",
                        hold.getAuthorizationId(), e.getMessage());
            }
        }

        log.info("[HoldExpiry] 완료: 만료 {}건 (threshold={}, expiryDays={})", expired, threshold, expiryDays);
        return expired;
    }
}
