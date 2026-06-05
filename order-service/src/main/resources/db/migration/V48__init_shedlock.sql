-- ShedLock — @Scheduled 분산 락 테이블.
-- replicas N 개 중 1 개만 실행 보장. 현재 prod 는 replicas: 1 이라 미래 HA 대비.
--
-- IF NOT EXISTS: 이전 V47__init_shedlock.sql (지금 V47 중복 fix 로 삭제됨) 이 일부 환경에
-- 이미 실행되어 shedlock 테이블을 만들어둔 경우 발생하는 'relation already exists' 회피.
CREATE TABLE IF NOT EXISTS opslab.shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

COMMENT ON TABLE opslab.shedlock IS 'ShedLock distributed lock — @SchedulerLock annotation 의 backing store';
