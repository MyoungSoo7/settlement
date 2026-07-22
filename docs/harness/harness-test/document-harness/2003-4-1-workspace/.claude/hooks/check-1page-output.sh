#!/usr/bin/env bash
# 회의록 → 1page 기획안 산출물 검수 hook.
#
# 트리거: Write 또는 Edit 도구 호출 후.
# 대상: 파일 경로에 "1page" 또는 "2003-4-3" 또는 "one-page"가 들어간 경우만 검사.
# 검사 항목:
#   1. 반드시 보존할 표현 3종이 큰따옴표 인용으로 살아 있는지.
#   2. "확인 필요 항목" 섹션이 존재하는지.
#   3. "60%" 같은 미확인 수치가 결론 본문(확인 필요 항목 위쪽)에 단정 형태로 들어갔는지.
#
# 출력: 위반이 있으면 exit code 2 + stderr 메시지로 Claude에게 재작성 요청.
#       위반이 없으면 조용히 종료(exit 0).

set -uo pipefail

# Claude Code가 hook에 넘기는 JSON을 stdin으로 받는다.
PAYLOAD="$(cat)"

# tool_input.file_path 추출 (jq 없이 단순 grep으로).
FILE_PATH="$(printf '%s' "$PAYLOAD" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"

if [ -z "${FILE_PATH:-}" ] || [ ! -f "$FILE_PATH" ]; then
  exit 0
fi

# 대상 파일 필터: 1page 기획안 산출물처럼 보일 때만 검사.
case "$FILE_PATH" in
  *1page*|*one-page*|*2003-4-3*)
    ;;
  *)
    exit 0
    ;;
esac

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

CONFIRM_SECTION_OK=1
if ! grep -qE '^#+[[:space:]]*확인[[:space:]]*필요[[:space:]]*항목' "$FILE_PATH"; then
  CONFIRM_SECTION_OK=0
fi

# 결론 본문(확인 필요 항목 섹션 위쪽)에서 "60%" 단정 사용 검사.
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
  echo "위 항목을 보정해서 다시 작성해 주세요."
} >&2

exit 2
