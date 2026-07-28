-- 기업대출 수동 REST 조작(상환 repay)의 멱등 저장소.
-- 더블클릭·재전송으로 같은 상환이 두 번 반영되면 미상환잔액이 이중 차감된다(코드리뷰 발견 #4).
-- RepayCorporateLoanService 의 findByIdForUpdate 비관적 락은 동시 요청을 직렬화할 뿐, 순차 재제출
-- (앞선 상환이 커밋된 뒤 같은 요청이 다시 도착)은 막지 못한다. 클라이언트가 보낸 Idempotency-Key 를
-- PK 로 원자적으로 선점(INSERT)해 두 번째 요청을 409 로 차단한다(LoanManualIdempotencyGuard).
-- 투자 수동 조작(investment_manual_operation_idempotency)과 동형 — 키 미지정 호출은 멱등 미적용(하위호환).
CREATE TABLE IF NOT EXISTS loan_manual_operation_idempotency (
    idempotency_key VARCHAR(200) PRIMARY KEY,
    endpoint        VARCHAR(200) NOT NULL,
    operator        VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL
);

COMMENT ON TABLE loan_manual_operation_idempotency IS
    '기업대출 수동 REST 멱등 키 선점 저장소 — LoanManualIdempotencyGuard (상환 이중 반영 차단)';
