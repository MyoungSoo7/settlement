package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.LoadCardCapturePort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveCardCapturePort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 매입 유스케이스 구현.
 *
 * <h3>매입 흐름</h3>
 * <ol>
 *   <li>captureId 멱등 체크 — 기존 매입 있으면 그대로 반환(이벤트 재발행 없음)</li>
 *   <li>홀드 비관적 락 조회({@code authorizationId})</li>
 *   <li>홀드 상태 검증(ACTIVE 또는 PARTIALLY_CAPTURED)</li>
 *   <li>홀드 상태 전이: capturedAmount == hold.amount → CAPTURED, 미만 → PARTIALLY_CAPTURED</li>
 *   <li>매입 레코드 저장</li>
 *   <li>홀드 갱신 저장</li>
 *   <li>Outbox 이벤트 발행({@code lemuel.card.captured}) — 같은 트랜잭션</li>
 * </ol>
 *
 * <h3>비관적 락</h3>
 * 같은 승인에 대한 동시 매입 요청이 두 개 들어왔을 때 둘 다 처리되지 않도록
 * {@code findByAuthorizationIdForUpdate} 로 홀드 행을 잠근다.
 */
@Service
public class CaptureHoldService implements CaptureHoldUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaptureHoldService.class);

    private final LoadCardCapturePort loadCardCapturePort;
    private final SaveCardCapturePort saveCardCapturePort;
    private final LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    private final SaveAuthorizationHoldPort saveAuthorizationHoldPort;
    private final PublishCardEventPort publishCardEventPort;

    public CaptureHoldService(LoadCardCapturePort loadCardCapturePort,
                               SaveCardCapturePort saveCardCapturePort,
                               LoadAuthorizationHoldPort loadAuthorizationHoldPort,
                               SaveAuthorizationHoldPort saveAuthorizationHoldPort,
                               PublishCardEventPort publishCardEventPort) {
        this.loadCardCapturePort = loadCardCapturePort;
        this.saveCardCapturePort = saveCardCapturePort;
        this.loadAuthorizationHoldPort = loadAuthorizationHoldPort;
        this.saveAuthorizationHoldPort = saveAuthorizationHoldPort;
        this.publishCardEventPort = publishCardEventPort;
    }

    @Override
    @Transactional
    public CardCapture capture(CaptureHoldCommand command) {
        // 1. captureId 멱등 체크 — 재전송 시 기존 매입 반환
        var existing = loadCardCapturePort.findByCaptureId(command.captureId());
        if (existing.isPresent()) {
            log.info("[Capture] 멱등 재처리(기존매입반환) captureId={}", command.captureId());
            return existing.get();
        }

        // 2. 홀드 비관적 락 조회
        AuthorizationHold hold = loadAuthorizationHoldPort
                .findByAuthorizationIdForUpdate(command.authorizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_AUTHORIZATION_NOT_FOUND));

        // 3. 홀드 상태 전이
        boolean full = hold.capture(command.capturedAmount());
        log.info("[Capture] authorizationId={} captureId={} amount={} full={}",
                command.authorizationId(), command.captureId(), command.capturedAmount(), full);

        // 4. 매입 레코드 생성 + 저장
        CardCapture capture = CardCapture.create(
                command.captureId(),
                command.authorizationId(),
                hold.getCardId(),
                hold.getCardAccountId(),
                hold.getHolderUserId(),
                command.capturedAmount(),
                command.merchantName() != null ? command.merchantName() : hold.getMerchantName(),
                command.capturedAt()
        );
        CardCapture saved = saveCardCapturePort.save(capture);

        // 5. 홀드 상태 갱신
        saveAuthorizationHoldPort.save(hold);

        // 6. Outbox 이벤트 발행 — 같은 트랜잭션
        publishCardEventPort.publishCaptured(saved, hold);

        return saved;
    }
}
