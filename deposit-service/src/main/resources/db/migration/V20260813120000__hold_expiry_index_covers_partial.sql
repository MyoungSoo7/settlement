-- V20260813120000: 만료 회수 스캔 인덱스가 PARTIALLY_CAPTURED 까지 덮게 한다 (deposit)
--
-- [무엇이 문제였나]
--   만료 스캔 부분 인덱스가 status = 'ACTIVE' 로만 걸려 있었고, 조회도 ACTIVE 만 봤다.
--   그런데 재원을 잡고 있는 상태는 ACTIVE 만이 아니다 — PARTIALLY_CAPTURED 도
--   remaining_amount > 0 인 동안 locked 를 잡는다(DepositHold.isActive() 가 두 상태를 함께 본다).
--   그 결과 "일부만 매입되고 나머지가 방치된 hold" 는 만료 시각이 지나도 회수 대상에 잡히지 않아
--   잔여 선점액이 영구히 잠겼다. 부분 캡처는 예외 상황이 아니라 카드 매입의 정상 경로라
--   이 누락은 시간이 갈수록 쌓인다.
--
-- [왜 조용한 손상인가]
--   total = available + locked 는 계속 성립하므로 잔고 검증으로는 잡히지 않는다. 잔고가
--   틀리는 게 아니라 가용액이 *덜 보이는* 방향이라, 셀러에게는 원인 없는 출금 실패로만 나타난다.
--
-- [인덱스 선택]
--   부분 인덱스를 유지하되 술어를 두 상태로 넓힌다. 전체 인덱스로 바꾸지 않는 이유는
--   종료 상태(CAPTURED/EXPIRED/VOIDED/RELEASED)가 시간이 갈수록 대다수가 되기 때문이다 —
--   그것까지 색인하면 만료 스캔이 보는 양이 계속 커진다.
--   IMMUTABLE 술어(등호·IN + 리터럴)라 부분 인덱스 조건으로 유효하다.

DROP INDEX IF EXISTS idx_deposit_holds_active_expiring;

CREATE INDEX idx_deposit_holds_unsettled_expiring
    ON deposit_holds (expires_at)
    WHERE status IN ('ACTIVE', 'PARTIALLY_CAPTURED');

COMMENT ON INDEX idx_deposit_holds_unsettled_expiring IS
    '만료 회수 배치 스캔 — 재원을 아직 잡고 있는 상태(ACTIVE, PARTIALLY_CAPTURED)만 색인';
