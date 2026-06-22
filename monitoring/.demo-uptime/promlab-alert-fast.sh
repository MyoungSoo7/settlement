#!/usr/bin/env bash
# 빠른 알림 데모 — HighHttpErrorRateFast(for:1m) 를 FIRING 까지. sleep 미사용.
set -u
GW=http://localhost:8090
RULES=http://localhost:9091/api/v1/rules
PROM=http://localhost:9091/api/v1/query
RULE=HighHttpErrorRateFast
enc(){ python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
errpct(){ curl -s -m5 "$PROM?query=$(enc '100*sum(rate(http_server_requests_seconds_count{job="lemuel",status=~"4..|5.."}[1m]))/sum(rate(http_server_requests_seconds_count{job="lemuel"}[1m]))')" \
  | python -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1]),1) if r else 0)"; }
state(){ curl -s -m5 "$RULES" \
  | python -c "import sys,json,os;d=json.load(sys.stdin)['data']['groups'];print(next((r.get('state','?') for g in d for r in g['rules'] if r['name']==os.environ['RULE']),'NA'))"; }
export RULE
SECONDS=0; last=-99
tick(){ if [ $((SECONDS-last)) -ge 10 ]; then local s e; s=$(state); e=$(errpct); printf '[t=%3ds] state=%-9s err%%=%s\n' "$SECONDS" "$s" "$e"; last=$SECONDS;
  if [ "$s" = firing ]; then echo "🔥 FIRING 도달 (t=${SECONDS}s) — for:1m 충족"; exit 0; fi; fi; }
echo "=== 빠른 알림 데모: /reservations/1 5xx 부하 + $RULE 폴링 ==="
echo "[t=  0s] state=$(state) (시작)"
while [ $SECONDS -lt 160 ]; do
  for i in 1 2 3 4 5 6 7 8; do curl -s -o /dev/null -m 3 "$GW/reservations/1" & done
  wait; tick
done
echo "=== 160s 도달, 종료 ==="
