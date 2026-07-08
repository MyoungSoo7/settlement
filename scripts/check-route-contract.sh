#!/usr/bin/env bash
# 라우트 정합성 게이트 — INC-2026-0708 재발 방지 조치 C.
#
# 프론트엔드가 호출하는 /api/* 경로가 백엔드 어느 컨트롤러 루트에도 속하지 않으면 CI 실패.
# (프론트가 존재하지 않는 API 를 부른 채 배포되는 사고 차단 — /api/settlements/search 사례:
#  백엔드 루트는 /api/settlements/query 였으므로 이 검사가 잡는다)
#
# 규칙: 프론트 경로 P 는 어떤 백엔드 @RequestMapping 루트 R 에 대해
#       P == R 또는 P 가 "R/" 로 시작해야 한다. 예외는 .route-contract-allowlist.
set -euo pipefail
cd "$(dirname "$0")/.."

# 1) 백엔드 컨트롤러 루트 (@RequestMapping 전체 값)
backend_roots=$(grep -rhoE '@RequestMapping\("(/api/[a-zA-Z0-9/_-]+)"\)' \
  --include='*.java' */src/main/java 2>/dev/null \
  | sed -E 's/@RequestMapping\("|"\)//g' | sort -u)

# 2) 프론트 호출 경로 리터럴 (템플릿 변수 ${...} 앞까지)
frontend_paths=$(grep -rhoE "['\"\`]/api/[a-zA-Z0-9/_-]+" \
  --include='*.ts' --include='*.tsx' frontend/src 2>/dev/null \
  | sed -E "s/^['\"\`]//" | sort -u)

allowlist_file=".route-contract-allowlist"
touch "$allowlist_file" 2>/dev/null || true

missing=0
echo "── 라우트 정합성 검사 (frontend 경로 ⊂ backend 루트) ──"
while IFS= read -r p; do
  [ -z "$p" ] && continue
  ok=0
  while IFS= read -r r; do
    [ -z "$r" ] && continue
    if [ "$p" = "$r" ] || case "$p" in "$r"/*) true;; *) false;; esac; then ok=1; break; fi
  done <<< "$backend_roots"
  if [ "$ok" = "1" ]; then
    echo "  ✅ $p"
  elif grep -qx "$p" "$allowlist_file" 2>/dev/null; then
    echo "  ⚠️  $p (allowlist)"
  else
    echo "  ❌ $p — 어떤 백엔드 @RequestMapping 루트에도 속하지 않음"
    missing=1
  fi
done <<< "$frontend_paths"

if [ "$missing" = "1" ]; then
  echo ""
  echo "실패: 프론트가 존재하지 않는 API 경로를 호출한다."
  echo "  → 백엔드 라우트를 만들거나 프론트 호출을 고쳐라."
  echo "    의도된 예외면 $allowlist_file 에 경로를 한 줄로 추가."
  exit 1
fi
echo "통과: 프론트 호출 경로가 전부 백엔드 루트에 속함."
