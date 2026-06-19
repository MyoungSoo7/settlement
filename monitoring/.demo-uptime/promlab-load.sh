#!/usr/bin/env bash
# Prometheus 실습용 부하 생성 + 타임라인 샘플러 (sleep 미사용, SECONDS 페이싱).
# 4단계: 평상(0-30s) → 버스트(30-75s) → 에러 파도(75-115s) → 진정(115-150s)
set -u
GW=http://localhost:8090
PROM=http://localhost:9091/api/v1/query

enc() { python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
val() { curl -s -m5 "$PROM?query=$(enc "$1")" | python -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1]),3) if r else 0)"; }

sample() {
  local tps p95 e5
  tps=$(val 'sum(rate(http_server_requests_seconds_count{job="lemuel"}[1m]))')
  p95=$(val 'histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket{job="lemuel"}[1m])))')
  e5=$(val 'sum(rate(http_server_requests_seconds_count{job="lemuel",status=~"5.."}[1m]))')
  printf '[t=%3ds] %-9s TPS=%-7s p95=%-7ss 5xx_rps=%s\n' "$SECONDS" "$1" "$tps" "$p95" "$e5"
}

SECONDS=0; last=-99
tick() { if [ $((SECONDS-last)) -ge 12 ]; then sample "$1"; last=$SECONDS; fi; }

phase() { # end_offset, path, parallelism, label
  local end=$1 path=$2 par=$3 label=$4
  while [ $SECONDS -lt "$end" ]; do
    for ((i=0;i<par;i++)); do curl -s -o /dev/null -m 2 "$GW$path" & done
    wait
    tick "$label"
  done
}

echo "=== promlab 부하 시작 ($(printf %s "$GW")) ==="
sample BASELINE
phase 30  /actuator/health 2 "평상"
phase 75  /actuator/health 12 "버스트"
phase 115 /api/orders/123  8 "에러파도"
phase 150 /actuator/health 2 "진정"
sample FINAL
echo "=== promlab 부하 종료 ==="
