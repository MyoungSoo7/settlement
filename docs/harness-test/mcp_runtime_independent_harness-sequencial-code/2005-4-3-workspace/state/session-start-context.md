[meeting-to-1page 하네스 v2 (2005-4-3) — 세션 시작 안내]

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

[workspace file check]
  - harness_v2_server.py: ok
  - contracts/requirements-contract.md: ok
  - contracts/spec-contract.md: ok
  - inputs/meeting.md: ok
  - .codex/hooks/review_gate.py: ok
  - .codex/agents/ask.toml: ok
  - .codex/agents/build.toml: ok
  - .codex/agents/review.toml: ok
  - .codex/skills/using-task-harness/SKILL.md: ok
  - .claude/skills/using-1page-harness/SKILL.md: ok
  - .claude/skills/meeting-to-1page/SKILL.md: ok
