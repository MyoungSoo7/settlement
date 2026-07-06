#!/usr/bin/env bash
# 시연용 원-샷 시드 스크립트.
# 1) docker compose up -d 가 떠있다고 가정
# 2) Postgres 에 demo seed SQL 삽입
# 3) seed_admin 으로 로그인해서 JWT 토큰 발급
# 4) Postman environment.json 에 자동 주입 (선택)

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# ─── 설정 (필요 시 ENV 로 override) ───
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5433}"
PG_DB="${PG_DB:-inter}"
PG_USER="${PG_USER:-${POSTGRES_USER:-lemuel}}"
PG_PASSWORD="${PG_PASSWORD:-${POSTGRES_PASSWORD:-lemuel}}"

ORDER_API="${ORDER_API:-http://localhost:8088}"
SETTLEMENT_API="${SETTLEMENT_API:-http://localhost:8082}"
GATEWAY_API="${GATEWAY_API:-http://localhost:8080}"

ADMIN_EMAIL="${ADMIN_EMAIL:-seed_admin@test.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-password123}"

echo "═══════════════════════════════════════════════════════"
echo "  Lemuel 시연용 시드 스크립트"
echo "═══════════════════════════════════════════════════════"

# ─── 1. 서비스 헬스체크 (최대 60초 대기) ───
echo "[1/4] 서비스 헬스체크..."
for i in {1..30}; do
    if curl -sf "$ORDER_API/actuator/health" > /dev/null 2>&1; then
        echo "  ✅ order-service healthy"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "  ❌ order-service 응답 없음 — docker compose ps 확인"
        exit 1
    fi
    sleep 2
done

for i in {1..30}; do
    if curl -sf "$SETTLEMENT_API/actuator/health" > /dev/null 2>&1; then
        echo "  ✅ settlement-service healthy"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "  ❌ settlement-service 응답 없음"
        exit 1
    fi
    sleep 2
done

# ─── 2. 시드 SQL 삽입 ───
echo "[2/4] 시드 SQL 삽입..."
export PGPASSWORD="$PG_PASSWORD"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
    -v ON_ERROR_STOP=1 \
    -f "$SCRIPT_DIR/seed-data.sql" \
    > /tmp/lemuel-seed.log 2>&1

if [ $? -eq 0 ]; then
    echo "  ✅ 시드 데이터 삽입 완료 (자세한 로그: /tmp/lemuel-seed.log)"
else
    echo "  ❌ 시드 SQL 실패. /tmp/lemuel-seed.log 확인"
    tail -20 /tmp/lemuel-seed.log
    exit 1
fi

# ─── 3. JWT 토큰 발급 ───
echo "[3/4] JWT 토큰 발급..."
LOGIN_RESPONSE=$(curl -sf -X POST "$ORDER_API/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>&1 || echo "FAIL")

if [ "$LOGIN_RESPONSE" = "FAIL" ]; then
    echo "  ⚠️  자동 로그인 실패 — 수동으로 토큰 발급 필요"
    echo "      curl -X POST $ORDER_API/auth/login \\"
    echo "        -H 'Content-Type: application/json' \\"
    echo "        -d '{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}'"
    JWT_TOKEN=""
else
    # 응답 구조에 따라 token / accessToken / jwt 등 키 시도
    if command -v jq >/dev/null 2>&1; then
        JWT_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // .accessToken // .jwt // empty' 2>/dev/null || echo "")
    else
        # jq 없으면 단순 파싱
        JWT_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -oE '"(token|accessToken|jwt)":"[^"]+' | head -1 | cut -d'"' -f4)
    fi
fi

if [ -n "$JWT_TOKEN" ]; then
    echo "  ✅ JWT 발급 완료"
else
    echo "  ⚠️  JWT 토큰 키를 찾지 못함 — Postman 에 수동 입력 필요"
    echo "      응답: $LOGIN_RESPONSE"
fi

# ─── 4. Postman environment 동적 갱신 (선택) ───
echo "[4/4] Postman environment 갱신..."
ENV_FILE="$SCRIPT_DIR/postman-environment.json"
if [ -f "$ENV_FILE" ] && command -v jq >/dev/null 2>&1; then
    TMP=$(mktemp)
    jq --arg token "$JWT_TOKEN" \
       --arg order "$ORDER_API" \
       --arg settlement "$SETTLEMENT_API" \
       --arg gateway "$GATEWAY_API" \
       '(.values[] | select(.key=="JWT_TOKEN").value) = $token |
        (.values[] | select(.key=="ORDER_API").value) = $order |
        (.values[] | select(.key=="SETTLEMENT_API").value) = $settlement |
        (.values[] | select(.key=="GATEWAY_API").value) = $gateway' \
       "$ENV_FILE" > "$TMP" && mv "$TMP" "$ENV_FILE"
    echo "  ✅ $ENV_FILE 갱신 완료"
fi

# ─── 출력 ───
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  🎬 시연 준비 완료"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "  ORDER_API       = $ORDER_API"
echo "  SETTLEMENT_API  = $SETTLEMENT_API"
echo "  JWT_TOKEN       = ${JWT_TOKEN:0:30}..."
echo ""
echo "  📋 시드된 데모 데이터:"
echo "     Payment 9001     — CAPTURED 분할결제 50,000 (POINT 5K + GIFT 10K + CARD 35K)"
echo "     Variant 9001/2/3 — 재고 50/50/30 (동시성 테스트용)"
echo "     Payout 9001/2/3  — REQUESTED 송금 대기"
echo "     Outbox 9001      — FAILED (DLQ 콘솔 시연용)"
echo ""
echo "  🎥 다음 단계: Loom / OBS 녹화 시작 → Postman 컬렉션 폴더 위→아래 클릭"
echo ""
