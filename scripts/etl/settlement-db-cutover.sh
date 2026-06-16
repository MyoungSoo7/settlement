#!/usr/bin/env bash
# ============================================================
# settlement-db-cutover.sh
# ADR 0020 Phase 4 — opslab → settlement_db 일회성 데이터 이관(ETL).
#
# settlement 소유 "원천(source-of-truth) 집계 테이블"만 data-only 로 복사한다.
# 스키마는 settlement-service 자체 Flyway(V1 baseline)가 이미 생성한 상태여야 한다
# (--data-only 라 스키마는 건드리지 않는다).
#
# 복사 대상 (원천 데이터):
#   settlements, settlement_adjustments, payouts, chargebacks,
#   ledger_entries, ledger_outbox, pg_reconciliation_runs,
#   pg_reconciliation_discrepancies, settlement_loan_deductions,
#   audit_logs, processed_events(컨슈머 멱등 커서 보존)
#
# 복사하지 않는 것:
#   - settlement_*_view (4종)  → 읽기모델. 컷오버 후 백필 엔드포인트로 재구축
#                                (POST /admin/settlement-projection/backfill).
#   - outbox_events            → 번들 모드에선 order 와 공유. 컷오버 전 "드레인"
#                                (전량 발행)으로 비운 뒤 settlement 는 빈 outbox 로 시작.
#   - settlement_index_queue   → 전이성 ES 색인 큐. ES 재색인으로 재생성.
#
# 사용:
#   SRC_URL="postgresql://user:pass@opslab-host:5432/opslab" \
#   DST_URL="postgresql://user:pass@settlement-db-host:5432/settlement_db" \
#   ./scripts/etl/settlement-db-cutover.sh
#
# 옵션:
#   DRY_RUN=1   복사 없이 각 테이블 행 수만 출력(사전 점검).
# ============================================================
set -euo pipefail

: "${SRC_URL:?SRC_URL(opslab) 가 필요합니다 예: postgresql://user:pass@host:5432/opslab}"
: "${DST_URL:?DST_URL(settlement_db) 가 필요합니다 예: postgresql://user:pass@host:5432/settlement_db}"

# 복사 순서 = 인트라-settlement FK 의존 순서(부모 먼저). 단일 pg_dump 호출이
# 의존성 정렬을 보장하지만, 가독성·DRY_RUN 카운트를 위해 명시한다.
TABLES=(
  settlements
  settlement_adjustments
  payouts
  chargebacks
  ledger_entries
  ledger_outbox
  pg_reconciliation_runs
  pg_reconciliation_discrepancies
  settlement_loan_deductions
  audit_logs
  processed_events
)

echo "==> settlement-db ETL 시작 (대상 ${#TABLES[@]}개 테이블)"

if [[ "${DRY_RUN:-0}" == "1" ]]; then
  echo "==> DRY_RUN: 원천(opslab) 행 수만 점검합니다."
  for t in "${TABLES[@]}"; do
    cnt=$(psql "$SRC_URL" -tAc "SELECT count(*) FROM public.${t};" 2>/dev/null || echo "ERR")
    printf "    %-36s %s\n" "$t" "$cnt"
  done
  echo "==> DRY_RUN 완료 (복사 미수행)."
  exit 0
fi

# 사전 안전 점검: 대상 집계 테이블이 비어 있어야 한다(중복 적재 방지).
existing=$(psql "$DST_URL" -tAc "SELECT count(*) FROM public.settlements;")
if [[ "$existing" != "0" ]]; then
  echo "!! 중단: settlement_db.settlements 에 이미 ${existing}건이 있습니다." >&2
  echo "!! 재실행이라면 대상 테이블을 먼저 TRUNCATE 하거나 클린 DB 로 진행하세요." >&2
  exit 1
fi

# --data-only: 스키마 미포함(Flyway 가 이미 생성). --table 다중 지정 → 의존성 정렬.
# 단일 pg_dump | psql 파이프 = 단일 논리 트랜잭션으로 적재(중간 실패 시 롤백).
DUMP_ARGS=(--data-only --no-owner --no-privileges)
for t in "${TABLES[@]}"; do
  DUMP_ARGS+=(--table="public.${t}")
done

echo "==> opslab 에서 data-only 덤프 → settlement_db 로 적재 중..."
pg_dump "$SRC_URL" "${DUMP_ARGS[@]}" \
  | psql "$DST_URL" --single-transaction --set ON_ERROR_STOP=on

echo "==> 적재 완료. 결과 행 수:"
for t in "${TABLES[@]}"; do
  cnt=$(psql "$DST_URL" -tAc "SELECT count(*) FROM public.${t};")
  printf "    %-36s %s\n" "$t" "$cnt"
done

cat <<'NEXT'

==> ETL 완료. 남은 컷오버 단계:
    1) settlement_*_view 읽기모델 재구축:
         POST /admin/settlement-projection/backfill  (ADMIN, order-service)
    2) ES 재색인으로 settlement_index_queue / 검색 인덱스 재생성.
    3) 대사(reconciliation)로 정합성 검증 후 트래픽 컷오버.
    자세한 절차: docs/runbooks/settlement-db-cutover.md
NEXT
