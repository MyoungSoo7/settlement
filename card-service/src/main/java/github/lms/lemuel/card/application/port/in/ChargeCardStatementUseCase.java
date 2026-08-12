package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.CardStatement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 매입 확정액을 청구 명세서에 반영하는 유스케이스.
 *
 * <p>청구 사이클의 <b>입력</b>이다. 이 경로가 없으면 명세서가 열리지도 채워지지도 않아
 * 마감(월 1회)·납부·연체 배치가 전부 빈손으로 돈다 — 실제로 그 상태였다(역산 PRD §12-E).
 *
 * <p>청구주기는 매입 시각(KST)의 연월이다. 해당 주기의 OPEN 명세서가 없으면 열고(멱등),
 * 있으면 그 명세서에 금액을 더한다.
 */
public interface ChargeCardStatementUseCase {

    /**
     * 매입액을 해당 청구주기 명세서에 반영한다.
     *
     * @param cardAccountId 카드계정
     * @param capturedAt    매입 확정 시각 — 이 시각의 연월(KST)이 청구주기다
     * @param amount        매입 금액(양수)
     * @return 반영된 명세서
     */
    CardStatement charge(Long cardAccountId, Instant capturedAt, BigDecimal amount);
}
