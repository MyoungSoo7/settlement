package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.VoidHoldUseCase;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 승인 취소(Void) 유스케이스 구현.
 *
 * <p>ACTIVE 또는 PARTIALLY_CAPTURED 홀드를 VOIDED 로 전환한다. 도메인의
 * {@link AuthorizationHold#voidHold()} 가 상태 전이 가드를 담당한다.
 */
@Service
public class VoidHoldService implements VoidHoldUseCase {

    private static final Logger log = LoggerFactory.getLogger(VoidHoldService.class);

    private final LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    private final SaveAuthorizationHoldPort saveAuthorizationHoldPort;

    public VoidHoldService(LoadAuthorizationHoldPort loadAuthorizationHoldPort,
                           SaveAuthorizationHoldPort saveAuthorizationHoldPort) {
        this.loadAuthorizationHoldPort = loadAuthorizationHoldPort;
        this.saveAuthorizationHoldPort = saveAuthorizationHoldPort;
    }

    @Override
    @Transactional
    public void voidHold(VoidHoldCommand command) {
        AuthorizationHold hold = loadAuthorizationHoldPort
                .findByAuthorizationIdForUpdate(command.authorizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_AUTHORIZATION_NOT_FOUND));

        hold.voidHold();

        saveAuthorizationHoldPort.save(hold);

        log.info("[Void] authorizationId={} reason={}", command.authorizationId(), command.reason());
    }
}
