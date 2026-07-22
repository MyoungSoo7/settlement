# Requirements Contract (chapter-02 integrated) — frozen

> 출처: `practice-materials/chapter-02/2002-4-3-interview-harness-v1.md`,
> `2002-2-3-requirements-table.md`, `2002-3-3-one-line-specs.md`.
> 2장 인터뷰 하네스를 다시 실행하지 않는다. 그 산출 구조(Seed = goal + constraints +
> acceptance_criteria, 그리고 evolve-step 사각지대 두 질문)를 v2 안의 계약으로 통합한다.
> 이 계약은 frozen이다. leaf 실행 중 direction을 바꾸지 않는다 (Ouroboros `Seed` frozen 원칙).
> 갱신은 오직 `route` 노드의 ask-back → requirements_update 경로로만 일어난다.

## 0. Contract Identity

| 항목 | 값 |
|---|---|
| contract | requirements |
| version | v1 |
| frozen | true |
| update_path | route.ask-back → requirements_update (사람 채택 반영) |

## 1. Define Contract (reader / decision / success signal / constraints)

2장 Seed 세 칸을 v2 실행 계약 네 칸으로 옮긴다.

| 칸 | 값 | 2장 근거 |
|---|---|---|
| reader (독자) | 1page를 받는 의사결정자 + 후속 실무자(시안·메일·CS 회신 담당) | Seed.goal 독자 범위, 2002-3-3 "독자 범위가 임원 + 후속 실무자로 넓어졌다" |
| decision (이 산출물이 가능케 할 결정) | 사내 교육 신청 페이지 개편 방향(강조 화면 + 자동 메일 두 갈래)을 채택할지 | meeting.md 회의 목적 |
| success signal (완료 신호) | 의사결정자가 추가 질문 없이 결정으로 넘어간다. 7칸이 근거와 함께 채워지고, 결정 항목과 확인 질문이 분리되어 있다 | Seed.acceptance_criteria "추가 질문 없이 결정으로", "결정 항목과 확인 질문이 분리" |
| constraints (제약) | 아래 §2 | Seed.constraints + 도메인 제약 |

## 2. Constraints (frozen)

- 회의록 원문과 확인된 요구사항 밖의 사실을 결론처럼 쓰지 않는다.
- 한 페이지(7칸) 분량을 넘기지 않는다.
- 단서 붙은 미확인 수치(예: CS 문의 60%)를 본문 6칸에 단정으로 넣지 않는다. 확인 필요 항목으로만 보존한다.
- 회의에서 범위 밖으로 합의된 항목(전체 리뉴얼·결제 모듈 모달 위치·환불 기준 명문화)을 해결 방향에 넣지 않는다.
- 미결정 항목을 결론 톤("~로 한다 / ~할 예정이다")으로 단정하지 않는다.

## 3. Acceptance Criteria (Seed 게이트 — frozen 골격, 값은 채워질 수 있음)

2장 Socrates 오케스트레이터의 Seed 게이트 세 칸을 그대로 둔다. acceptance_criteria 중
**측정 가능한 성공 지표**는 회의록만으로 닫히지 않는 칸이라, 초기값은 "미확정"이다.
이 칸이 미확정인 채로 review에 들어가면 L6가 ask-back을 낸다 (목표 문장 vs 측정 지표 구분).

| 칸 | 상태 | 값 |
|---|---|---|
| goal | 확정 | 회의록 한 건을 1page 7칸 초안으로 옮긴다 (신청 직후 환불·일정 문의를 줄이는 개편 방향) |
| acceptance.behavioral | 확정 | 의사결정자가 추가 질문 없이 결정으로 넘어간다 / 확인 질문이 결정 항목과 분리된다 |
| acceptance.metric | **미확정 (ask-back 대상)** | 성공 기준을 측정 지표로 쓸지, 쓴다면 측정 단위·기간은 무엇인지 — 회의록 밖 결정 |

## 4. 2장 질문 구조 (ask-back 때 requirements_update가 쓰는 것)

requirements가 갱신될 자리는 §3의 `acceptance.metric` 한 칸이다. 갱신은 2장 evolve-step의
사각지대 두 질문 + 사람 채택(☑/☒) 구조를 그대로 쓴다. 자동 추측으로 채우지 않는다.

### Q1. 이 정의가 놓친 사용자 시각 (3)
- (a) 후속 실무자가 성공 여부를 측정 가능한 지표로 추적할 수 있어야 하는가
- (b) 의사결정자가 "줄인다"의 기준선(현재 문의량)을 함께 봐야 하는가
- (c) CS팀이 집계 기간·집계 기준에 합의해야 하는가

### Q2. 이 정의로 잘려 나간 가능성 (3)
- (d) "신청 직후 환불·일정 문의를 줄인다"가 목표 문장인가, 측정 지표인가
- (e) 측정 지표라면 단위(비율·건수)와 기간(예: 4주)을 어디서 가져오는가
- (f) 기준선 수치(CS 문의 60% 추정)는 출처 확정 전까지 지표에 쓸 수 없는가

> 사람 채택 예시(2002-3-3 형식): ☑ (d) 측정 지표로 본다 / ☑ (e) 단위=비율, 기간=다음 회의까지 4주 / ☒ (f) 기준선은 별도 회신 대기.
> 채택 결과가 `inputs/answers.md`로 들어오면 requirements_update가 §3 acceptance.metric을 채우고 v2로 freeze 한다.

## 5. 잠금 규칙 (frozen contract)

- 이 계약의 §1 reader/decision/success, §2 constraints는 한 run 안에서 바뀌지 않는다.
- §3 acceptance.metric만 ask-back → requirements_update로 한 번 채워진다. 채워진 뒤에는 다시 frozen.
- leaf(ask/build/review)는 이 계약을 입력으로만 읽는다. 본문에서 임의로 바꾸면 collect_handoff가 비계약 상태로 보고 버린다.
