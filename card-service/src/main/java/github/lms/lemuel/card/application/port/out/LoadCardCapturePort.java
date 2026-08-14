package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardCapture;

import java.util.Optional;

/**
 * 카드 매입 조회 포트.
 *
 * <p>멱등 체크({@link #findByCaptureId}) 와 승인 기준 매입 이력 조회를 제공한다.
 */
public interface LoadCardCapturePort {

    /**
     * 자연키({@code captureId})로 매입 조회 — 멱등 체크 전용.
     */
    Optional<CardCapture> findByCaptureId(String captureId);
}
