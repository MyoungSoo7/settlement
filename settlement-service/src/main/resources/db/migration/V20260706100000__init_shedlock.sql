-- ShedLock 분산 락 테이블 (@SchedulerLock backing store — SchedulingLockConfig 참조).
-- order-service 는 V48__init_shedlock.sql 로 opslab.shedlock 을 만들었지만, settlement_db 분리 시
-- 이 테이블이 누락돼 SettlementScheduler·PayoutScheduler·HoldbackReleaseScheduler·LedgerOutboxPoller 의
-- 락 획득이 매 tick "relation public.shedlock does not exist" 로 실패하고 있었다 (E2E 검증 중 발견).
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

COMMENT ON TABLE shedlock IS 'ShedLock distributed lock — @SchedulerLock annotation 의 backing store';
