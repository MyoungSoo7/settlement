package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.CardCapture;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 카드 매입 유스케이스 포트.
 *
 * <p>VAN 네트워크가 매입 확정을 푸시할 때 호출된다.
 * 승인 홀드({@code authorizationId}) 를 소진하고 {@code lemuel.card.captured} 이벤트를 발행한다.
 *
 * <p>멱등 키: {@code captureId} — 같은 매입 재전송 시 기존 레코드를 반환하고 이벤트를 재발행하지 않는다.
 */
public interface CaptureHoldUseCase {

    CardCapture capture(CaptureHoldCommand command);

    /**
     * 매입 커맨드.
     *
     * @param captureId      VAN 매입번호 — 멱등 자연키
     * @param authorizationId 매입 대상 승인번호
     * @param capturedAmount 매입 금액(양수). 승인 금액보다 적으면 부분매입
     * @param merchantName   가맹점 이름(optional)
     * @param capturedAt     VAN 매입 확정 시각
     */
    record CaptureHoldCommand(
            String captureId,
            String authorizationId,
            BigDecimal capturedAmount,
            String merchantName,
            Instant capturedAt
    ) {
    }
}
