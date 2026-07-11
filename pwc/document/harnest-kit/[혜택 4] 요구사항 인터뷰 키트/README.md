# 요구사항 인터뷰 키트

## 한 줄

막연한 요청 한 줄을 입력으로 닫힌 Seed YAML(goal · constraints · acceptance_criteria) + ontology 한 묶음을 만들어내는 스킬 8개를 한 세트로 묶었다. `/interview-harness` 한 번 호출로 1~5 사이클이 자동으로 돈다.

## 이 키트가 다루는 범위

**다루는 것**
- 막연한 한 줄 → Wonder/Reflect/Refine/Restate 사이클 → Seed v1
- Seed v1 → 사각지대 두 질문 → 사용자 채택 → Seed v2
- Seed → 의미 경계 ontology (idea/boundary/properties)
- ontology 유사도 0.85 이상으로 수렴할 때까지 Ralph 루프 반복

**다루지 않는 것**
- 닫힌 Seed로부터의 코드 생성 / 구현
- 운영·배포·테스트 자동화
- 위 8개 스킬 바깥의 분업 (그 단계는 가이드 02 / 03 / chapter 3 이후로 넘어간다)

## 이 폴더의 자료

| 파일 | 용도 |
|---|---|
| `README.md` | 이 문서 |
| `다이어그램.md` | 8개 스킬의 호출 그래프와 스크래치 파일 흐름 |
| `설치-가이드.md` | `.claude/skills/`에 옮기는 절차, 스크래치 디렉토리 준비 |
| `사용-예시.md` | "task 관리 CLI 만들고 싶다" 한 줄로 끝까지 돌리는 워크스루 |
| `skills/<name>/SKILL.md` × 8 | 키트의 8개 스킬 본문 |

## 8개 스킬의 역할 요약

| # | 스킬 | 역할 | 위치 |
|---|---|---|---|
| 1 | `interview-harness` | 마스터 오케스트레이터. 상위 3개 호출(`/socrates`, `/evolve-step`, `/ontology`)을 Ralph 루프로 묶고, `/socrates` 내부의 4개 스킬 경계를 유지한다 | 최상위 |
| 2 | `socrates` | Wonder/Reflect/Refine/Restate를 한 묶음으로 돌려 Seed 생성 | harness 안 |
| 3 | `wonder` | 한 단어/요청에 숨어 있는 의미 후보 3개 이상 발산 | socrates 안 |
| 4 | `reflect` | 후보와 사용자 의도를 한 쌍씩 비교 | socrates 안 |
| 5 | `refine` | 의미 차이를 사용자 승인 shared meaning 한 줄로 합침 | socrates 안 |
| 6 | `restate` | shared meaning을 실행 가능한 goal 한 문장으로 재진술 | socrates 안 |
| 7 | `evolve-step` | Seed의 사각지대 6개 후보 → 사용자 채택 → Seed v_n+1 | harness 안 |
| 8 | `ontology` | Seed.goal의 의미 경계(idea/boundary/properties) 정의 | harness 안 |

## 스크래치 파일

세 파일이 8개 스킬을 묶는다.

- `.claude/scratch/socrates.md` — 사이클 누적 + 최상위 `## Seed` + 최상위 `## Ontology` 각 한 벌(누적 X, 매번 교체).
- `.claude/scratch/evolve-step.md` — 사이클별 사각지대 audit 로그 누적.
- `.claude/scratch/interview-harness.md` — 직전 ontology 스냅샷, 비교 로그.

자세한 흐름은 `다이어그램.md` 참고.

## 어디서 쓰는가

Chapter 2 [요구사항 인터뷰] 전 모듈. 특히:
- Clip 2002-1-3 ~ 2002-4-3 — `/wonder`, `/reflect`, `/refine`, `/restate` 4개 스킬을 `/socrates`로 묶고, 다시 `/socrates`, `/evolve-step`, `/ontology`를 마스터 하네스로 묶는 실습의 *완성 산출물*이다.
- Chapter 3 진입 — 이 키트가 떨어뜨린 final_seed가 3장 [실행 하네스]의 입력이 된다.

## 사용 순서

1. `설치-가이드.md`로 8개 SKILL.md를 본인 저장소의 `.claude/skills/` 아래에 옮긴다.
2. `.claude/scratch/` 디렉토리를 만든다.
3. `사용-예시.md`의 시나리오 한 줄로 `/interview-harness`를 호출해 끝까지 돌려본다.
4. 사이클이 1~5 사이에서 수렴하고 final_seed YAML이 떨어지는지 검증한다.
5. sub 스킬 단독 호출(`/socrates`, `/wonder`, …)이 여전히 작동하는지 회귀 점검.

## 완료 기준

- `/interview-harness` 한 번으로 막연한 요청 → 닫힌 Seed + ontology가 나온다.
- 같은 입력을 두 번 넣어도 출력 YAML의 키 구조가 같다 (재현성).
- 사각지대 채택은 사용자가 결정한다 (자동 채택 금지).
- Stop rule: similarity ≥ 0.85 + Cycle > 5 안전밸브 둘 다 작동.
- 8개 스킬 단독 호출이 각각 동작한다.

## 가이드 01과의 관계

- 가이드 01 (문서형 하네스 템플릿) = md형 SKILL.md 일반 빈 양식.
- 이 키트 (가이드 04) = 그 양식으로 한 도메인(요구사항 인터뷰)을 끝까지 채운 *완성 산출물*.

01을 먼저 읽으면 이 키트의 8개 SKILL.md가 왜 그런 7칸 구조로 적혀 있는지 보인다.
