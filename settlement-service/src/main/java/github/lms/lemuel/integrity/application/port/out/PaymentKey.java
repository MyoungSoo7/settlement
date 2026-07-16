package github.lms.lemuel.integrity.application.port.out;

import java.math.BigDecimal;

/**
 * INV-12 프로젝션 diff 용 결제 키 — 키(id)+금액만 (PII 없음).
 * 체크섬 불일치 시에만 order/프로젝션 양측에서 페이지네이션으로 수집해 id 집합 diff 를 낸다.
 */
public record PaymentKey(long paymentId, BigDecimal amount) {
}
