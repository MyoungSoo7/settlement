#!/usr/bin/env bash
# 5xx 유발 실습 — /reservations/1 (다운스트림 미가동 → 게이트웨이 500). sleep 미사용.
set -u
GW=http://localhost:8090
PROM=http://localhost:9091/api/v1/query
enc() { python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
val() { curl -s -m5 "$PROM?query=$(enc "$1")" | python -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1]),3) if r else 0)"; }

sample() {
  local tot e5 ratio
  tot=$(val 'sum(rate(http_server_requests_seconds_count{job="lemuel"}[1m]))')
  e5=$(val 'sum(rate(http_server_requests_seconds_count{job="lemuel",status=~"5.."}[1m]))')
  ratio=$(val '100 * sum(rate(http_server_requests_seconds_count{job="lemuel",status=~"4..|5.."}[1m])) / sum(rate(http_server_requests_seconds_count{job="lemuel"}[1m]))')
  printf '[t=%3ds] %-9s TPS=%-7s 5xx_rps=%-7s err%%=%s\n' "$SECONDS" "$1" "$tot" "$e5" "$ratio"
}

SECONDS=0; last=-99
tick(){ if [ $((SECONDS-last)) -ge 10 ]; then sample "$1"; last=$SECONDS; fi; }
phase(){ local end=$1 path=$2 par=$3 label=$4; while [ $SECONDS -lt "$end" ]; do for ((i=0;i<par;i++)); do curl -s -o /dev/null -m 3 "$GW$path" & done; wait; tick "$label"; done; }

echo "=== promlab 5xx 부하 시작 ==="
sample BASELINE
phase 20 /actuator/health 2 "평상"
phase 70 /reservations/1   8 "5xx파도"
phase 90 /actuator/health 2 "진정"
sample FINAL
echo "=== 종료 ==="
