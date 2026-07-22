#!/usr/bin/env python3
"""SessionStart hook (v2) for the 2005-4-3 meeting-to-1page harness v2.

4장 codex_workspace_2004-4-3/.codex/hooks/session_start.py를 계승한다.
다른 점: v2는 frozen contract(requirements/spec)와 capability graph, route 규칙을
시작 컨텍스트로 주입하고 state/session-start-context.md에 남긴다.
"""

from __future__ import annotations
import json
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parents[2]


def marker(path: str) -> str:
    return "ok" if (WORKSPACE / path).exists() else "missing"


CONTEXT = """[meeting-to-1page 하네스 v2 (2005-4-3) — 세션 시작 안내]

이 workspace는 4장 하네스 v1을 5장 capability graph로 승격한 v2다.
메인 세션이 상태를 소유하고, leaf(ask/build/review)는 disposable 실행 단위다.

capability graph (한 번 실행):
  requirements_contract -> spec_contract -> session_surface -> structured_contract
  -> state_store.load_or_init -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter
  -> collect_handoff -> review_gate -> review_parallel(L5 ∥ L6) -> merge_verdict -> route

frozen contract (Ouroboros Seed 원칙, 실행 중 direction 불변):
  - contracts/requirements-contract.md  (2장 통합: reader/decision/success/constraints)
  - contracts/spec-contract.md          (3장 통합: 7칸 output contract + 고정·남김·질문)

review gate 순서: format -> evidence(meeting.md:NN 줄 실재) -> false-negative(60% 단정).
  통과해야만 review_parallel(L5 인용 보존 ∥ L6 도메인/범위/FN)로 넘어간다 (3단 조기차단).

route: pass(exit) / rework(retry, checkpoint->return node) /
       ask-back(requirements_update->spec 갱신->resume target) / blocked exit(last_good_checkpoint + pending).

검수 hook:
  python3 .codex/hooks/review_gate.py handoffs/handoff-L5-review.md --leaf L5
  python3 .codex/hooks/review_gate.py handoffs/handoff-L6-review.md --leaf L6

Codex custom agents: .codex/agents/{ask,build,review}.toml
메타 스킬: .codex/skills/using-task-harness/SKILL.md
"""


def main() -> int:
    checks = [
        "harness_v2_server.py",
        "contracts/requirements-contract.md",
        "contracts/spec-contract.md",
        "inputs/meeting.md",
        ".codex/hooks/review_gate.py",
        ".codex/agents/ask.toml",
        ".codex/agents/build.toml",
        ".codex/agents/review.toml",
        ".codex/skills/using-task-harness/SKILL.md",
        ".claude/skills/using-1page-harness/SKILL.md",
        ".claude/skills/meeting-to-1page/SKILL.md",
    ]
    body = CONTEXT + "\n[workspace file check]\n" + "\n".join(
        f"  - {p}: {marker(p)}" for p in checks
    )
    state_dir = WORKSPACE / "state"
    state_dir.mkdir(exist_ok=True)
    (state_dir / "session-start-context.md").write_text(body + "\n", encoding="utf-8")
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": body,
        },
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
