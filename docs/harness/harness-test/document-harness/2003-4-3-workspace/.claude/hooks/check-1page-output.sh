#!/usr/bin/env bash
# PostToolUse hook — 회의록 → 1page 기획안 산출물 검수.
#
# 트리거: Write 또는 Edit 도구 호출 직후.
# 대상: 파일 경로에 "1page" / "one-page" / "2003-4-3" 중 하나가 들어간 경우만.
# 검사 3종:
#   ① 반드시 보존할 표현 3줄이 큰따옴표 인용 형태로 살아 있는지.
#   ② "확인 필요 항목" 섹션이 존재하는지.
#   ③ "60%" 같은 미확인 수치가 확인 필요 항목 위쪽 결론 본문에
#      단정 형태로 들어갔는지.
#
# 출력: 위반 시 exit 2 + stderr 메시지. 위반 없으면 조용히 exit 0.

set -uo pipefail

PAYLOAD="$(cat)"

FILE_PATH="$(printf '%s' "$PAYLOAD" \
  | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]+"' \
  | head -1 \
  | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"

if [ -z "${FILE_PATH:-}" ] || [ ! -f "$FILE_PATH" ]; then
  exit 0
fi

# 대상 파일 필터.
case "$FILE_PATH" in
  *1page*|*one-page*|*2003-4-3*)
    ;;
  *)
    exit 0
    ;;
esac

# 보존 표현 3줄.
PHRASES=(
  '"신청 직후 환불·일정 문의를 줄인다"'
  '"페이지 전체 리뉴얼은 이번 범위가 아니다"'
  '"환불 기준 자체가 명문화돼 있지 않다"'
)

MISSING=()
for p in "${PHRASES[@]}"; do
  if ! grep -qF "$p" "$FILE_PATH"; then
    MISSING+=("$p")
  fi
done

# 확인 필요 항목 섹션.
CONFIRM_SECTION_OK=1
if ! grep -qE '^#+[[:space:]]*확인[[:space:]]*필요[[:space:]]*항목' "$FILE_PATH"; then
  CONFIRM_SECTION_OK=0
fi

# 미확인 수치 단정 검사 (확인 필요 항목 섹션 위쪽 본문 한정).
NUM_LEAK=0
if grep -qE '^#+[[:space:]]*확인[[:space:]]*필요[[:space:]]*항목' "$FILE_PATH"; then
  HEAD_PART="$(awk '/^#+[[:space:]]*확인[[:space:]]*필요[[:space:]]*항목/{exit} {print}' "$FILE_PATH")"
  if printf '%s' "$HEAD_PART" | grep -qE '60[[:space:]]*%'; then
    NUM_LEAK=1
  fi
fi

if [ ${#MISSING[@]} -eq 0 ] && [ "$CONFIRM_SECTION_OK" -eq 1 ] && [ "$NUM_LEAK" -eq 0 ]; then
  exit 0
fi

{
  echo "[1page 검수 hook] $FILE_PATH 에서 검수 항목 위반 감지."
  if [ ${#MISSING[@]} -gt 0 ]; then
    echo "  - 보존 표현 누락:"
    for p in "${MISSING[@]}"; do
      echo "      $p"
    done
  fi
  if [ "$CONFIRM_SECTION_OK" -eq 0 ]; then
    echo "  - '확인 필요 항목' 섹션이 보이지 않음. 미결정 항목을 결론 칸과 분리해 주세요."
  fi
  if [ "$NUM_LEAK" -eq 1 ]; then
    echo "  - '60%' 수치가 확인 필요 항목 위쪽 본문에 결론처럼 사용됨. 출처가 확정될 때까지 확인 필요 항목으로만 옮겨 주세요."
  fi
  echo "위 항목만 정확히 보정해서 같은 파일을 다시 Edit 해 주세요. 전체 재작성 금지."
} >&2

exit 2
