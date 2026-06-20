#!/usr/bin/env bash
# 시연 후 데모 데이터만 정리 (재녹화용). 9000+ ID 만 삭제 — 기존 시드 데이터는 유지.

set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5433}"
PG_DB="${PG_DB:-inter}"
PG_USER="${PG_USER:-${POSTGRES_USER:-lemuel}}"
PG_PASSWORD="${PG_PASSWORD:-${POSTGRES_PASSWORD:-lemuel}}"

export PGPASSWORD="$PG_PASSWORD"

echo "[reset] 데모 데이터 (9000+) 삭제 중..."

psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
-- FK 순서 고려: 자식 → 부모
DELETE FROM opslab.outbox_events WHERE id >= 9000;
DELETE FROM opslab.payouts WHERE id >= 9000;
DELETE FROM opslab.payment_tenders WHERE payment_id >= 9000;
DELETE FROM opslab.payments WHERE id >= 9000;
DELETE FROM opslab.settlements WHERE id >= 9000;
DELETE FROM opslab.orders WHERE id >= 9000;
DELETE FROM opslab.product_variants WHERE id >= 9000;
DELETE FROM opslab.products WHERE id >= 9000;
COMMIT;

SELECT '✅ 데모 데이터 정리 완료' AS msg;
SQL

echo "[reset] 완료. ./seed.sh 로 재시작 가능."
