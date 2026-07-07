#!/usr/bin/env bash
# settlement-copilot — Codex CLI 설치/동기화 스크립트.
# 멱등: 몇 번을 재실행해도 중복 누적 없이 같은 상태로 수렴한다.
#
#   bash settlement-copilot/install-codex.sh          # 전체 설치
#   bash settlement-copilot/install-codex.sh --sync   # $CODEX_HOME 아티팩트만 재동기화 (git hook 이 자동 호출)
#
# 하는 일:
#   1. 루트 AGENTS.md 에 코어 규칙을 마커 블록으로 병합 (재실행 시 블록 교체)
#   2. commands/*.md → $CODEX_HOME/prompts/  (상대경로 절대화 + 저장소 가드 문구 삽입)
#   3. skills/      → $CODEX_HOME/skills/
#   4. $CODEX_HOME/config.toml 의 MCP 블록을 마커 방식으로 갱신 (기존 토큰/URL 보존)
#   5. git hooks — pre-commit 가드 + post-merge/post-checkout 자동 재동기화
#   6. 설치 매니페스트 기록 → doctor(scripts/doctor.mjs) 가 드리프트 감지에 사용
#   7. codex execpolicy rules(codex/prod-db.rules) 검증
set -euo pipefail

SYNC_ONLY=false
if [ "${1:-}" = "--sync" ]; then SYNC_ONLY=true; fi

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$PLUGIN_DIR/.." && pwd)"
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"

# Windows(Git Bash)에서는 /c/... 경로를 C:/... 로 변환해 TOML/훅에 기록한다.
PLUGIN_DIR_NATIVE="$PLUGIN_DIR"
if command -v cygpath >/dev/null 2>&1; then
  PLUGIN_DIR_NATIVE="$(cygpath -m "$PLUGIN_DIR")"
fi

BEGIN_MARK='<!-- settlement-copilot:begin (managed by install-codex.sh - 직접 수정 금지) -->'
END_MARK='<!-- settlement-copilot:end -->'
TOML_BEGIN='# settlement-copilot:begin (managed by install-codex.sh - 직접 수정 금지)'
TOML_END='# settlement-copilot:end'

# ── 1) AGENTS.md 병합 — 마커 블록 교체 방식 ─────────────────────────────────
if $SYNC_ONLY; then
  echo "[1/7] --sync 모드 — AGENTS.md 병합 건너뜀"
else
  AGENTS_TARGET="$REPO_ROOT/AGENTS.md"
  tmp="$(mktemp)"
  if [ -f "$AGENTS_TARGET" ]; then
    awk -v b="$BEGIN_MARK" -v e="$END_MARK" '
      $0 == b { skip = 1 }
      !skip   { print }
      $0 == e { skip = 0 }
    ' "$AGENTS_TARGET" > "$tmp"
  fi
  # 끝의 빈 줄을 정리해 재실행 시 빈 줄이 누적되지 않게 한다.
  body="$(cat "$tmp" 2>/dev/null || true)"
  {
    if [ -n "$body" ]; then printf '%s\n\n' "$body"; fi
    printf '%s\n' "$BEGIN_MARK"
    cat "$PLUGIN_DIR/AGENTS.md"
    printf '%s\n' "$END_MARK"
  } > "$AGENTS_TARGET"
  rm -f "$tmp"
  echo "[1/7] AGENTS.md 병합 → $AGENTS_TARGET"
fi

# ── 2) 커맨드 → 커스텀 프롬프트 (경로 절대화 + 저장소 가드) ─────────────────
mkdir -p "$CODEX_HOME/prompts"
GUARD_NOTE="> **저장소 가드**: 이 커맨드는 Lemuel 정산 저장소 전용이다 (플러그인: \`$PLUGIN_DIR_NATIVE\`). 현재 작업 대상이 이 저장소가 아니면 실행하지 말고 그렇다고 답하라."
for f in "$PLUGIN_DIR"/commands/*.md; do
  base="$(basename "$f")"
  awk -v note="$GUARD_NOTE" '
    BEGIN { fm = 0 }
    { print }
    /^---[[:space:]]*$/ { fm++; if (fm == 2) { print ""; print note } }
  ' "$f" | sed "s|settlement-copilot/|$PLUGIN_DIR_NATIVE/|g" > "$CODEX_HOME/prompts/$base"
done
echo "[2/7] commands → $CODEX_HOME/prompts/ ($(ls "$PLUGIN_DIR"/commands/*.md | wc -l | tr -d ' ')개, 경로 절대화)"

# ── 3) skills ──────────────────────────────────────────────────────────────
mkdir -p "$CODEX_HOME/skills"
cp -R "$PLUGIN_DIR"/skills/. "$CODEX_HOME/skills/"
echo "[3/7] skills → $CODEX_HOME/skills/"

# ── 4) MCP 서버 등록 — 마커 블록 갱신, 기존 토큰/URL 보존 ────────────────────
CONFIG_TOML="$CODEX_HOME/config.toml"
touch "$CONFIG_TOML"

# 기존 블록(마커 or 레거시 무마커)에서 env 값을 건져 보존한다.
OLD_BLOCK="$(awk -v b="$TOML_BEGIN" -v e="$TOML_END" '$0==b{f=1} f{print} $0==e{f=0}' "$CONFIG_TOML")"
if [ -z "$OLD_BLOCK" ]; then
  OLD_BLOCK="$(awk '/^\[mcp_servers\.settlement-copilot\]/{f=1; print; next} f && /^\[/{f=0} f{print}' "$CONFIG_TOML")"
fi
pick() { printf '%s\n' "$OLD_BLOCK" | grep -o "$1 *= *\"[^\"]*\"" | head -1 || true; }
SB="$(pick SETTLEMENT_BASE_URL)"; [ -n "$SB" ] || SB='SETTLEMENT_BASE_URL = "http://localhost:8082"'
OB="$(pick ORDER_BASE_URL)";      [ -n "$OB" ] || OB='ORDER_BASE_URL = "http://localhost:8088"'
AT="$(pick COPILOT_ADMIN_TOKEN)"
IK="$(pick INTERNAL_API_KEY)"
ENV_LINE="env = { $SB, $OB"
[ -n "$AT" ] && ENV_LINE="$ENV_LINE, $AT"
[ -n "$IK" ] && ENV_LINE="$ENV_LINE, $IK"
ENV_LINE="$ENV_LINE }"

tmp="$(mktemp)"
awk -v b="$TOML_BEGIN" -v e="$TOML_END" '
  $0 == b { skip = 1; next }
  skip && $0 == e { skip = 0; next }
  skip { next }
  /^\[mcp_servers\.settlement-copilot\]/ { legacy = 1; next }
  legacy && /^\[/ { legacy = 0 }
  legacy { next }
  { print }
' "$CONFIG_TOML" > "$tmp"
body="$(cat "$tmp")"
rm -f "$tmp"
{
  if [ -n "$body" ]; then printf '%s\n\n' "$body"; fi
  printf '%s\n' "$TOML_BEGIN"
  printf '[mcp_servers.settlement-copilot]\n'
  printf 'command = "node"\n'
  printf 'args = ["%s/mcp/server/index.mjs"]\n' "$PLUGIN_DIR_NATIVE"
  printf '%s\n' "$ENV_LINE"
  printf '%s\n' "$TOML_END"
} > "$CONFIG_TOML"
echo "[4/7] config.toml MCP 블록 갱신 → $CONFIG_TOML (토큰/URL 보존)"

# ── 5) git hooks — pre-commit 가드 + 자동 재동기화 ──────────────────────────
HOOK_DIR="$(cd "$REPO_ROOT" && git rev-parse --git-path hooks 2>/dev/null || true)"
if [ -z "$HOOK_DIR" ]; then
  echo "[5/7] git 저장소가 아님 — git hooks 건너뜀"
else
  case "$HOOK_DIR" in
    /*|[A-Za-z]:*) : ;;
    *) HOOK_DIR="$REPO_ROOT/$HOOK_DIR" ;;
  esac
  mkdir -p "$HOOK_DIR"
  REL_GUARD="${PLUGIN_DIR#"$REPO_ROOT"/}/hooks/guards/pre-commit.mjs"
  REL_INSTALL="${PLUGIN_DIR#"$REPO_ROOT"/}/install-codex.sh"

  HOOK_FILE="$HOOK_DIR/pre-commit"
  if [ -f "$HOOK_FILE" ] && grep -qF 'settlement-copilot' "$HOOK_FILE"; then
    echo "[5/7] pre-commit 가드 이미 설치됨 — 건너뜀"
  else
    if [ ! -f "$HOOK_FILE" ]; then printf '#!/bin/sh\n' > "$HOOK_FILE"; fi
    printf 'node "%s" || exit 1   # settlement-copilot guard\n' "$REL_GUARD" >> "$HOOK_FILE"
    chmod +x "$HOOK_FILE"
    echo "[5/7] pre-commit 가드 설치 → $HOOK_FILE"
  fi

  for h in post-merge post-checkout; do
    HF="$HOOK_DIR/$h"
    if [ -f "$HF" ] && grep -qF 'settlement-copilot' "$HF"; then
      echo "[5/7] $h 자동 재동기화 이미 설치됨 — 건너뜀"
    else
      if [ ! -f "$HF" ]; then printf '#!/bin/sh\n' > "$HF"; fi
      printf 'bash "%s" --sync >/dev/null 2>&1 || true   # settlement-copilot sync\n' "$REL_INSTALL" >> "$HF"
      chmod +x "$HF"
      echo "[5/7] $h 자동 재동기화 설치 → $HF"
    fi
  done
fi

# ── 6) 설치 매니페스트 — doctor 드리프트 감지용 ─────────────────────────────
MANIFEST="$CODEX_HOME/.settlement-copilot-manifest.json"
if command -v git >/dev/null 2>&1; then
  {
    printf '{\n'
    printf '  "pluginDir": "%s",\n' "$PLUGIN_DIR_NATIVE"
    printf '  "installedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "files": {\n'
    first=1
    for f in "$PLUGIN_DIR"/AGENTS.md "$PLUGIN_DIR"/commands/*.md "$PLUGIN_DIR"/skills/*/SKILL.md \
             "$PLUGIN_DIR"/mcp/server/index.mjs "$PLUGIN_DIR"/hooks/guards/*.mjs "$PLUGIN_DIR"/codex/*.rules; do
      [ -f "$f" ] || continue
      rel="${f#"$PLUGIN_DIR"/}"
      h="$(git hash-object --no-filters "$f")"
      if [ $first -eq 1 ]; then first=0; else printf ',\n'; fi
      printf '    "%s": "%s"' "$rel" "$h"
    done
    printf '\n  }\n}\n'
  } > "$MANIFEST"
  echo "[6/7] 설치 매니페스트 → $MANIFEST"
else
  echo "[6/7] git 없음 — 매니페스트 건너뜀 (doctor 드리프트 감지 불가)"
fi

# ── 7) execpolicy rules 검증 (지원 시) ──────────────────────────────────────
if command -v codex >/dev/null 2>&1 \
   && codex execpolicy check --rules "$PLUGIN_DIR/codex/prod-db.rules" psql >/dev/null 2>&1; then
  echo "[7/7] execpolicy rules 검증 통과 — codex execpolicy check --rules \"$PLUGIN_DIR_NATIVE/codex/prod-db.rules\" <명령>"
else
  echo "[7/7] codex execpolicy 미지원/검증 실패 — rules 파일만 동봉 (codex/prod-db.rules)"
fi

echo
echo "완료. Codex CLI 를 재시작하면 /recon-check 등 커맨드와 settlement-copilot MCP 도구를 쓸 수 있습니다."
echo "설치 상태 점검: node \"$PLUGIN_DIR_NATIVE/scripts/doctor.mjs\" (또는 /copilot-doctor)"
echo "운영 API 를 쓰려면 config.toml env 에 COPILOT_ADMIN_TOKEN / INTERNAL_API_KEY 를 추가하세요 (재실행해도 보존됩니다)."
