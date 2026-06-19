#!/usr/bin/env bash
# 알림 실습 — 5xx 지속 부하로 HighHttpErrorRate(for:5m) 를 FIRING 까지. sleep 미사용.
set -u
GW=http://localhost:8090
RULES=http://localhost:9091/api/v1/rules
PROM=http://localhost:9091/api/v1/query
enc(){ python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
errpct(){ curl -s -m5 "$PROM?query=$(enc '100*sum(rate(http_server_requests_seconds_count{job="lemuel",status=~"4..|5.."}[1m]))/sum(rate(http_server_requests_seconds_count{job="lemuel"}[1m]))')" \
  | python -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1]),1) if r else 0)"; }
state(){ curl -s -m5 "$RULES" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data']['groups'];print(next((r.get('state','?') for g in d for r in g['rules'] if r['name']=='HighHttpErrorRate'),'NA'))"; }

SECONDS=0; last=-99
tick(){
  if [ $((SECONDS-last)) -ge 15 ]; then
    local s e; s=$(state); e=$(errpct)
    printf '[t=%3ds] state=%-9s err%%=%s\n' "$SECONDS" "$s" "$e"
    last=$SECONDS
    if [ "$s" = firing ]; then echo "🔥 FIRING 도달 (t=${SECONDS}s) — for:5m 충족"; exit 0; fi
  fi
}
echo "=== 알림 실습 시작: /reservations/1 5xx 지속 부하 + 상태 폴링 ==="
sample0_s=$(state); echo "[t=  0s] state=$sample0_s (시작)"
while [ $SECONDS -lt 420 ]; do
  for i in 1 2 3 4 5 6 7 8; do curl -s -o /dev/null -m 3 "$GW/reservations/1" & done
  wait; tick
done
echo "=== 420s 도달, 종료 ==="
