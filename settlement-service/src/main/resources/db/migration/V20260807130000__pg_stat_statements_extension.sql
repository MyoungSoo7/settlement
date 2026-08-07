-- V20260807130000: pg_stat_statements 확장 생성 — 쿼리 단위 실측 (DB 성능 감사 ④)
--
-- compose 의 settlement-db 가 shared_preload_libraries=pg_stat_statements,auto_explain 으로 기동한다.
-- 확장은 DB 로컬 오브젝트라 여기서 생성해 배선을 완성한다 — 이후 정산 핫패스의 인덱스 유무 판정은
-- 추정이 아니라 pg_stat_statements(누적 통계)·auto_explain(실계획 로그)으로 한다.
--
-- Testcontainers(IT)에는 preload 가 없지만 CREATE EXTENSION 자체는 성공한다(뷰만 생성되고
-- 수집이 안 될 뿐) — 마이그레이션 체인은 어느 환경에서나 안전하게 통과한다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
