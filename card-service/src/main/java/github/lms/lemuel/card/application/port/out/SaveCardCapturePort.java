package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardCapture;

/**
 * 카드 매입 저장 포트.
 *
 * <p>{@code capture_id} 는 UNIQUE 제약이 있어 중복 저장 시 DB 가 최후로 막는다.
 */
public interface SaveCardCapturePort {

    CardCapture save(CardCapture capture);
}
