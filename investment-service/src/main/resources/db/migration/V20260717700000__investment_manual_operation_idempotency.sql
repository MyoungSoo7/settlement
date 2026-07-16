-- 투자 수동 REST 조작(주문 신청 place / 집행 execute / 취소 cancel)의 멱등 저장소.
-- 더블클릭·재전송으로 같은 조작이 두 번 집행되는 것을, 클라이언트가 보낸 Idempotency-Key 를
-- PK 로 원자적으로 선점(INSERT)해 차단한다 (InvestmentManualIdempotencyGuard). @Version 낙관적 락은
-- 두 요청이 같은 시작 상태를 관측하는 전이 직전 경합 창을 완전히 막지 못하므로, 어댑터 계층의 키
-- 선점을 앞단에 둬 이중 집행(이중 이벤트 발행)을 원천 차단한다. 키 미지정 호출은 멱등 미적용(하위호환).
CREATE TABLE IF NOT EXISTS investment_manual_operation_idempotency (
    idempotency_key VARCHAR(200) PRIMARY KEY,
    endpoint        VARCHAR(200) NOT NULL,
    operator        VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL
);

COMMENT ON TABLE investment_manual_operation_idempotency IS
    '투자 수동 REST 멱등 키 선점 저장소 — InvestmentManualIdempotencyGuard';
