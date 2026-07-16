-- 운영자 수동 REST 조작(payout retry/cancel, chargeback accept/reject 등)의 멱등 저장소.
-- 더블클릭·재전송으로 같은 조작이 두 번 집행되는 것을, 클라이언트가 보낸 Idempotency-Key 를
-- PK 로 원자적으로 선점(INSERT)해 차단한다 (ManualIdempotencyGuard). 상태가드만으로는 전이 직전
-- 경합을 완전히 막지 못하므로, 어댑터 계층의 키 선점으로 이중 실행을 원천 차단한다.
-- 테스트(create-drop)는 ManualOperationRecord 엔티티로 테이블이 생성되므로 이 스크립트는 운영/실DB 용.
CREATE TABLE IF NOT EXISTS manual_operation_idempotency (
    idempotency_key VARCHAR(200) PRIMARY KEY,
    endpoint        VARCHAR(200) NOT NULL,
    operator        VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE manual_operation_idempotency IS '운영자 수동 REST 멱등 키 선점 저장소 — ManualIdempotencyGuard';
