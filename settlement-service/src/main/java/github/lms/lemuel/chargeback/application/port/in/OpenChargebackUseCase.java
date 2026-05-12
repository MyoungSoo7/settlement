package github.lms.lemuel.chargeback.application.port.in;

import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;

import java.math.BigDecimal;

/**
 * 신규 분쟁 등록.
 *
 * <p>호출 경로 2 가지:
 * <ul>
 *   <li>PG webhook 어댑터 — {@code source=PG_WEBHOOK}, {@code pgChargebackId} 필수 (멱등 키)</li>
 *   <li>운영자 수동 등록 (PG 통지 누락·시연용) — {@code source=MANUAL}</li>
 * </ul>
 *
 * <p>{@code pgChargebackId} 가 이미 등록된 분쟁이면 기존 {@link Chargeback} 을 그대로 반환 (멱등).
 */
public interface OpenChargebackUseCase {

    Chargeback open(OpenChargebackCommand command);

    record OpenChargebackCommand(
            Long paymentId,
            Long settlementId,           // null 가능 — 정산 생성 전 분쟁 발생 시
            BigDecimal amount,
            ChargebackReason reasonCode,
            String reasonDetail,
            ChargebackSource source,
            String pgChargebackId        // PG_WEBHOOK 일 때만 필수
    ) { }
}
