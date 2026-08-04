package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.RefundHoldUseCase;
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
 * 카드 환불(Refund) 유스케이스 구현.
 *
 * <p>CAPTURED 또는 PARTIALLY_CAPTURED 홀드를 REFUNDED 로 전환한다. 도메인의
 * {@link AuthorizationHold#refund()} 가 상태 전이 가드를 담당한다.
 *
 * <p>환불은 취소(void)와 달리 매입 후에만 가능하다. ACTIVE 홀드 환불 시도는
 * 도메인 예외가 발생하며 이는 호출자(VAN 어댑터)의 잘못된 요청이다.
 */
@Service
public class RefundHoldService implements RefundHoldUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundHoldService.class);

    private final LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    private final SaveAuthorizationHoldPort saveAuthorizationHoldPort;

    public RefundHoldService(LoadAuthorizationHoldPort loadAuthorizationHoldPort,
                             SaveAuthorizationHoldPort saveAuthorizationHoldPort) {
        this.loadAuthorizationHoldPort = loadAuthorizationHoldPort;
        this.saveAuthorizationHoldPort = saveAuthorizationHoldPort;
    }

    @Override
    @Transactional
    public void refund(RefundHoldCommand command) {
        AuthorizationHold hold = loadAuthorizationHoldPort
                .findByAuthorizationIdForUpdate(command.authorizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_AUTHORIZATION_NOT_FOUND));

        hold.refund();

        saveAuthorizationHoldPort.save(hold);

        log.info("[Refund] authorizationId={} reason={}", command.authorizationId(), command.reason());
    }
}
