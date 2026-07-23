#!/usr/bin/env python3
"""SessionStart hook — 작업분해 하네스 세션 시작 안내.

트리거: Claude Code 세션 시작, resume, /clear, compact 직후.
역할:   JSON {"additionalContext": ...} 를 stdout으로 내보내 하네스 규약을
        모델 컨텍스트 상단에 주입한다. PostToolUse가 사후 검수라면, 이쪽은 사전 안내.

입력:   stdin으로 JSON (source 등). 파싱 실패해도 안내는 그대로 내보낸다.
출력:   exit 0. additionalContext 필드가 컨텍스트로 주입된다.
"""
import json
import sys

CONTEXT = """\
[작업분해 하네스 v1 (claude_workspace_2004-4-3) — 세션 시작 안내]

이 워크스페이스에서 회의록을 1page 7칸 초안으로 옮길 때, 메인 세션은 상태판이고
sub agent는 소모품이다. 리프마다 같은 네 단계를 돈다.

  1) handoff 양식으로 sub agent에 brief를 보낸다
  2) sub agent가 handoff 한 장으로 결과를 돌려준다 (handoffs/ 에 기록)
  3) review gate(format -> evidence -> FN 보정)로 통과시킨다
  4) route: pass=다음 리프 / rework=같은 sub agent에 좁힌 재의뢰
            / ask-back=ask sub agent 또는 사람 / exit=메인 보고

루프는 도구와 분리한다. sub agent 띄우는 법은 도구가 바뀌면 갈아끼우고, 위 네 단계는
그대로 둔다.

sub agent 띄우기 (지금 도구 = Claude Code):
  - Task 도구로 .claude/agents 의 ask / build / review subagent를 띄운다.
  - brief = 4-2 handoff 양식(inputs/handoff-template.md)을 채운 한 장.

읽고 시작할 것:
  - 루프·계약·route : task-decomposition-harness-v1.md
                      .claude/skills/using-task-harness/SKILL.md
  - 그래프(노드·edge·route) : inputs/agent-execution-graph.md
  - review gate 단계 : inputs/review-gate.md
  - 근거 대조 원문 : inputs/meeting.md

결정론 경계 (정직하게):
  handoffs/ 에 쓰는 산출물은 PostToolUse hook(.claude/hooks/review_gate.py)이
  format, meeting.md 인용 줄 실재, 금지값(60%) 단정만 결정론으로 잡는다.
  인용이 주장을 실제로 뒷받침하는지, 성공 기준이 목표인지 지표인지 같은 의미 판정은
  review sub agent(LLM-judge)가 본다. 같은 입력에 같은 verdict를 끝까지 보장하는
  재현 gate는 5장 Ouroboros 몫이다.
"""


def main():
    try:
        json.load(sys.stdin)
    except Exception:
        pass
    print(json.dumps({"additionalContext": CONTEXT}, ensure_ascii=False))
    sys.exit(0)


if __name__ == "__main__":
    main()
