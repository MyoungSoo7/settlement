---
name: interview-harness
description: |
  Socrates + Evolve-step + Ontology를 Ralph 루프로 묶어 막연한 요청 한 줄을 닫힌 Seed + Ontology 한 묶음으로 수렴시킨다. socrates.md에 닫힌 Seed가 이미 있으면 Socrates 단계는 건너뛴다. ontology 유사도가 0.85 이상이거나 5사이클을 넘으면 종료.
  Triggers (KO): 인터뷰 하네스, /interview-harness, 하네스로 돌려줘, Seed로 수렴, Seed 닫아줘
  Triggers (EN): interview-harness, run interview harness, converge seed, socratic harness, close this into a seed
  Do NOT use when: 의미 발산만 필요 → /wonder. 초기 Seed만 생성 → /socrates. 사각지대 점검만 → /evolve-step. 의미 경계만 정의 → /ontology.
  vs socrates: Socrates는 Seed v1을 만든다. Interview Harness는 그 뒤로 evolve-step과 ontology 수렴까지 간다.
---

# Interview Harness

## 역할

이 스킬은 마스터 오케스트레이터다. 상위 3개 호출(`/socrates`, `/evolve-step`, `/ontology`)을 정해진 순서로 묶고, `/socrates` 내부의 4개 스킬(`/wonder`, `/reflect`, `/refine`, `/restate`) 경계를 유지한다. ontology 유사도가 임계값을 넘을 때까지 사이클을 반복하며, 각 스킬의 내부 흐름을 다시 적지 않고 호출만 한다.

## 스크래치 파일

- `.claude/scratch/socrates.md` — canonical 파이프라인. 최상위 `## Seed`와 `## Ontology` 각 한 벌, 사이클 섹션들.
- `.claude/scratch/evolve-step.md` — 사이클별 사각지대 audit 로그.
- `.claude/scratch/interview-harness.md` — 직전 ontology 스냅샷과 similarity 비교 로그.

## Stage 1 — Seed Gate

`.claude/scratch/socrates.md`를 읽는다.

최상위 `## Seed` 섹션이 없거나 YAML의 `goal` / `constraints` / `acceptance_criteria` 중 하나라도 누락이거나 `empty`이면 `/socrates`를 호출한다.

세 칸이 모두 채워져 있으면 `/socrates`는 건너뛰고 Stage 2로 간다.

## Stage 2 — Convergence Loop (최대 5 사이클)

매 사이클 같은 구조:

1. 최상위 `## Ontology`가 이미 있다면 `.claude/scratch/interview-harness.md`의 `## Previous Ontology` 섹션에 스냅샷으로 저장.
2. `/evolve-step` 호출 → Seed v_(n+1).
3. `/ontology` 호출 → ontology v_(n+1).
4. 직전 ontology와 비교해 similarity 계산.
5. similarity ≥ 0.85이면 종료, `stop_reason = "convergence"`.
6. Cycle > 5이면 종료, `stop_reason = "safety_valve"`.

첫 사이클은 직전 ontology가 없으므로 비교를 건너뛰고 다음 사이클로 간다.

## Similarity 계산

세 항목 평균.

- **idea**: 동일하면 1, 아니면 0. Seed.goal에서 그대로 가져오므로 goal이 변하지 않으면 자동 1점.
- **boundary**: 직전 boundary와 이번 boundary가 같은 뜻인지 사용자에게 물어 확인. 사용자가 그렇다고 답할 때만 1.
- **properties**: Jaccard overlap = |intersection| / |union|.

## 출력

```yaml
final_seed:
  goal: ...
  constraints:
    - ...
  acceptance_criteria:
    - ...
  ontology:
    idea: ...
    boundary: ...
    properties:
      - ...
      - ...
      - ...
cycles: <N>
final_similarity: <0.0~1.0>
stop_reason: convergence | safety_valve
```

## 원칙

1. sub 스킬을 호출만 한다. 내부 흐름을 다시 적지 않는다.
2. `socrates.md`의 `## Seed`와 `## Ontology`는 매번 통째 교체. 최신 한 벌만 유지.
3. 직전 ontology는 `interview-harness.md`에 보관해 다음 사이클 비교용으로 쓴다.
4. 수렴 임계값은 0.85로 고정.
5. Cycle > 5 안전밸브는 절대 넘기지 않는다.
6. `/evolve-step`의 사각지대 채택은 사용자가 한다. 자동 채택 금지.
7. boundary 동치 판단은 사용자 확인 없이 결정하지 않는다.
8. sub 스킬 단독 호출이 여전히 동작해야 한다.
