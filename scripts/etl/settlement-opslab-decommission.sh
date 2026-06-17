#!/usr/bin/env bash
# ============================================================
# settlement-opslab-decommission.sh
# ADR 0020 Phase 5.5 — 컷오버·안정화 후 opslab 에서 settlement 소유 테이블 제거.
#
# ⚠️ 비가역(DROP). 반드시 (1) 컷오버 완료 + 롤백 창 종료, (2) Phase 5.2 대사 무드리프트,
#    (3) opslab 백업(pg_dump/스냅샷) 확보 이후에만 실행한다.
#
# 안전장치:
#   - 기본 DRY-RUN: 무엇을 지울지 + 행 수만 출력(실제 DROP 안 함).
#   - settlement_db 대조(SETTLEMENT_URL 제공 시): 각 테이블이 settlement_db 에 opslab 이상
#     행 수로 존재할 때만 후보로 인정. 미달이면 중단(미이관 데이터 보호).
#   - 실제 DROP 은 CONFIRM=DROP 환경변수가 있을 때만. 단일 트랜잭션.
#
# 사용:
#   # 1) 점검(기본): 지울 대상·행 수 확인
#   OPSLAB_URL="postgresql://u:p@opslab:5432/opslab" \
#   SETTLEMENT_URL="postgresql://u:p@settlement-db:5432/settlement_db" \
#   ./scripts/etl/settlement-opslab-decommission.sh
#
#   # 2) 실제 제거(백업 후): CONFIRM=DROP 추가
#   OPSLAB_URL=... SETTLEMENT_URL=... CONFIRM=DROP ./scripts/etl/settlement-opslab-decommission.sh
# ============================================================
set -euo pipefail

: "${OPSLAB_URL:?OPSLAB_URL 이 필요합니다 예: postgresql://u:p@opslab:5432/opslab}"

# DROP 대상 = settlement_db(V1 baseline)로 이관 완료된 settlement 도메인 테이블만.
# 자식(FK 참조) 먼저 → 부모 순. CASCADE 로 잔여 의존도 정리.
# KEEP(공유, order 가 계속 사용): outbox_events, processed_events, audit_logs, shedlock, batch_run_history.
DROP_TABLES=(
  settlement_adjustments
  settlement_loan_deductions
  pg_reconciliation_discrepancies
  pg_reconciliation_runs
  ledger_outbox
  ledger_entries
  chargebacks
  payouts
  settlement_index_queue
  settlement_payment_view
  settlement_order_view
  settlement_user_view
  settlement_product_view
  settlements
)

echo "==> opslab settlement 테이블 decommission (대상 ${#DROP_TABLES[@]}개)"
echo "    KEEP(공유): outbox_events, processed_events, audit_logs, shedlock, batch_run_history"
echo "    REVIEW(별도): settlement_schedule_config — settlement_db baseline 부재, 미사용 확인 후 수동 판단"
echo

count() { psql "$1" -tAc "SELECT count(*) FROM public.${2};" 2>/dev/null || echo "N/A"; }

blocked=0
for t in "${DROP_TABLES[@]}"; do
  src=$(count "$OPSLAB_URL" "$t")
  if [[ -n "${SETTLEMENT_URL:-}" ]]; then
    dst=$(count "$SETTLEMENT_URL" "$t")
    flag=""
    # 대조: settlement_db 행 수가 opslab 미만이면 미이관 의심 → 차단
    if [[ "$src" =~ ^[0-9]+$ && "$dst" =~ ^[0-9]+$ && "$dst" -lt "$src" ]]; then
      flag="  ⛔ settlement_db 미달 — 미이관 의심"
      blocked=1
    fi
    printf "    %-34s opslab=%-8s settlement_db=%-8s%s\n" "$t" "$src" "$dst" "$flag"
  else
    printf "    %-34s opslab=%-8s (settlement_db 대조 생략)\n" "$t" "$src"
  fi
done

if [[ "$blocked" == "1" ]]; then
  echo; echo "!! 중단: settlement_db 로 이관되지 않은 것으로 의심되는 테이블이 있습니다." >&2
  echo "!! 백필/ETL 로 먼저 정합화한 뒤 재실행하세요." >&2
  exit 1
fi

if [[ "${CONFIRM:-}" != "DROP" ]]; then
  echo; echo "==> DRY-RUN 완료. 실제 제거는 백업 확보 후 CONFIRM=DROP 으로 재실행하세요."
  exit 0
fi

echo; echo "==> CONFIRM=DROP — opslab 에서 단일 트랜잭션으로 DROP 합니다..."
{
  echo "BEGIN;"
  for t in "${DROP_TABLES[@]}"; do
    echo "DROP TABLE IF EXISTS public.${t} CASCADE;"
  done
  echo "COMMIT;"
} | psql "$OPSLAB_URL" --set ON_ERROR_STOP=on

echo "==> 완료. 잔여 확인:"
for t in "${DROP_TABLES[@]}"; do
  exists=$(psql "$OPSLAB_URL" -tAc "SELECT to_regclass('public.${t}') IS NOT NULL;")
  printf "    %-34s exists=%s\n" "$t" "$exists"
done

cat <<'NEXT'

==> decommission 완료. 후속:
    - order-service 의 settlement 테이블 생성 마이그레이션(V2/V4/V5/V6/V35/V43~45/V49/
      V20260616120000~160000)은 이력 보존상 남는다. 신규 opslab 부트스트랩 시 빈 테이블이
      재생성되나 order 코드가 사용하지 않으므로 무해(미사용 잔여). 그린필드에선 생략 권장.
    - settlement_schedule_config 는 별도 검토 후 수동 처리.
    상세: docs/runbook/settlement-db-decommission.md
NEXT
