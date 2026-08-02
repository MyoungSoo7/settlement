package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 마스터 한도 변경 결과. {@code clamped} 는 하향이 Σ서브한도 하한에 걸려 잘렸음을 뜻하며,
 * limit_changed 이벤트에 실려 나간다 — 운영자가 "왜 요청한 만큼 안 내려갔나"를 알 수 있어야 한다.
 */
public record LimitChangeResult(BigDecimal appliedLimit, boolean clamped) {
}
