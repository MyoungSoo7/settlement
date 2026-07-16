# 2006-1-2. 팀 AI 인프라 요구사항 명세

## Source Mapping

- Curriculum: `curriculum.md` row `2006-1-2` in `6-1. 팀 단위 아키텍처 확장` defines the clip as `[Case Study] Zeude: 팀 단위 모니터링과 동기화`, with key message `하네스와 스킬을 팀 전체와 공유하고 실행을 모니터링하는 인프라` and required output `팀 AI 인프라 요구사항 명세`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that leaders own the playground: problem definition, team-wide AI operating structure, and domain judgment matter more than hand-coding speed.
- Reference repo: Zeude is approved by `decks/approved-source-checklist.md` for chapter 06 through `lecture-decks.seed.yaml` reference repo `https://github.com/zep-us/zeude`; use only source-described roles and repo-observable paths, without adoption or scale claims.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Zeude Source References

Use this Chapter 06 citation format for every Zeude-derived setup step, task, example, and expected outcome in this file:

- Primary source: `curriculum.md` row `2006-1-2` defines Zeude as the Chapter 06 case study for `팀 단위 모니터링과 동기화`.
- Reference source: `lecture-decks.seed.yaml` maps Chapter 06 to `https://github.com/zep-us/zeude`; `decks/chapter-06.html` slides 8-11 notes list the verified Zeude repo paths used for observation.
- Guardrail: `decks/approved-source-checklist.md` allows Zeude only for team sharing, monitoring, synchronization infrastructure, and repo-observable paths. Do not infer install, runtime behavior, adoption, scale, benchmark, price, discount, or production readiness.

| Worksheet location | Zeude-derived use | Source metadata |
|---|---|---|
| 준비물: `Chapter 06 deck의 Zeude 사례 관찰 메모` | Setup context for observing Zeude before writing team infrastructure requirements | Primary source: `curriculum.md` row `2006-1-2`; Reference source: `lecture-decks.seed.yaml` reference repo `https://github.com/zep-us/zeude` and `decks/chapter-06.html` slides 8-11 notes `Repo Reference · verified Zeude paths`; Guardrail: `decks/approved-source-checklist.md` Zeude mapping |
| 준비물: `Zeude에 없는 기능이나 검증되지 않은 도입 효과` guardrail | Boundary that prevents unsupported Zeude feature/effect claims | Guardrail: `decks/approved-source-checklist.md` Zeude mapping; Audit metadata: `decks/source-fidelity-audit.md` Zeude verification-boundary notes |
| Practice task and 단계별 진행 steps 2-4 | Observe Zeude structure and translate sharing, monitoring, synchronization, and operating boundaries into team requirements | Primary source: `curriculum.md` row `2006-1-2`; Reference source: `decks/chapter-06.html` slides 8-11 notes `Zeude 팀 하네스 설계 포인트` and `학습자 관찰 포인트`; Guardrail: `decks/approved-source-checklist.md` Zeude mapping |
| 작성 템플릿 section `Source Observation` | Four observation lenses: team sharing, monitoring, synchronization, operating criteria | Primary source: `curriculum.md` row `2006-1-2`; Reference source: `decks/chapter-06.html` slides 8-11 notes; Guardrail: `decks/approved-source-checklist.md` Zeude mapping |
| Example section `Source Observation` and requirement categories | Fictional learner example that reuses Zeude-derived lenses without claiming Zeude runtime or adoption results | Primary source: `curriculum.md` row `2006-1-2`; Reference source: `decks/chapter-06.html` slides 8-11 notes; Guardrail: `decks/approved-source-checklist.md` Zeude mapping |
| 완료 기준 and 제출 체크리스트 | Expected outcome that Zeude observations are limited to sharing, monitoring, synchronization, operating constraints, and roadmap handoff | Primary source: `curriculum.md` rows `2006-1-2` and `2006-1-3`; Reference source: `decks/chapter-06.html` slides 8-16 notes; Guardrail: `decks/approved-source-checklist.md` Zeude mapping |

## Practice Task and Prompt

- Task: create `팀 AI 인프라 요구사항 명세` for clip `2006-1-2` so the learner can apply the curriculum message `하네스와 스킬을 팀 전체와 공유하고 실행을 모니터링하는 인프라` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2006-1-2을 도와줘.

Curriculum mapping:
- Module: 6-1. 팀 단위 아키텍처 확장
- Clip: [Case Study] Zeude: 팀 단위 모니터링과 동기화
- Key message: 하네스와 스킬을 팀 전체와 공유하고 실행을 모니터링하는 인프라
- Required output: 팀 AI 인프라 요구사항 명세

Interview mapping:
- Use the interview framing that leaders own the playground: problem definition, team-wide AI operating structure, and domain judgment matter more than hand-coding speed.

Task:
내 입력을 바탕으로 `팀 AI 인프라 요구사항 명세` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

Zeude 사례를 "왜 팀 하네스인가" 세 동기로 읽고, 우리 팀 AI 인프라 요구사항으로 옮긴다. ① 공유했는데 안 쓰이던 것을 0터치로 깔리게(공유·동기화), ② 잘 못 쓰는 사람을 모니터링으로 케어(관측), ③ 비개발자가 만든 스킬이 랭킹에 오르며 퍼지게(빌더·랭킹) — 이 셋에 운영 기준을 더한 네 축으로 요구사항을 쓴다. Zeude는 claude·codex를 둘 다 감싸는 shim + 대시보드로 이 세 동기를 코드로 푼다.

## Zeude 세팅 참고 (관찰용)

요구사항을 쓰기 전에 팀 하네스가 실제로 어떻게 깔리는지 한 번 본다. 설치를 검증하는 게 아니라 "우리 팀이면 누가 무엇을 준비하나"를 가늠하기 위한 공개 절차 관찰이다(README Quick Start 기준).

- 운영자(1회): repo clone → `.env`에 Supabase(설정·사용자)·ClickHouse(분석) 자격증명 → `cd zeude/dashboard && pnpm install && pnpm dev` → Supabase·ClickHouse 마이그레이션 → OTEL collector 기동.
- 팀원(각 머신, 한 줄): `curl -fsSL https://<대시보드>/releases/install.sh | ZEUDE_AGENT_KEY=zd_xxx bash` → `~/.zeude/bin`에 claude·codex shim + PATH 등록 + `~/.zeude/credentials`(agent_key). 이후 평소대로 `claude`·`codex` 실행.
- 핵심: 팀원은 새 도구를 안 배운다. shim이 가운데서 스킬·hook을 동기화하고 OTEL로 측정한다. 같은 agent_key가 claude·codex를 한 사용자로 묶는다.

→ 적용 질문: 우리 팀 운영자는 누구이고, agent_key는 어떻게 나눠줄까? 첫 동기화 대상 스킬은 무엇인가? (이 답이 §3 공유·동기화 행이 된다.)

## 권장 시간

35분

## 준비물

- 2006-1-1 AI Native 마인드셋 체크리스트
- 2005-4-3 최종 하네스 운영 가이드
- Chapter 06 deck의 Zeude 사례 관찰 메모 (Source note (Zeude): Primary source: `curriculum.md` row `2006-1-2`; Reference source: `lecture-decks.seed.yaml` reference repo `https://github.com/zep-us/zeude` and `decks/chapter-06.html` slides 8-11 notes; Guardrail: `decks/approved-source-checklist.md`)
- 팀에서 공유해야 할 하네스, 스킬, 규칙, 검수 기준 후보
- 공유, 실행 모니터링, 동기화, 운영 기준에 대한 현재 팀의 불편함
- 금지사항: Zeude에 없는 기능이나 검증되지 않은 도입 효과를 요구사항 근거로 쓰지 않기

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 대상 팀 |  |
| 공유할 하네스/스킬 |  |
| 현재 운영 문제 |  |
| 필요한 관측 정보 |  |
| 필요한 공유 방식 |  |
| 필요한 동기화 방식 |  |
| 필요한 버전 또는 운영 관리 |  |
| 필요한 사용 기준 |  |
| 접근 권한/보안 제약 |  |
| 성공기준 |  |

## 단계별 진행

1. 2006-1-1 체크리스트에서 팀과 공유할 하네스, 규칙, 검수 기준을 가져온다.
2. Chapter 06 deck의 Zeude 관찰 메모에서 공유, 모니터링, 동기화 역할을 확인한다.
3. 우리 팀의 현재 운영 문제를 관측 공백, 공유 공백, 동기화 공백, 빌더·랭킹 공백, 리뷰 공백으로 나눈다.
4. 각 공백을 요구사항으로 바꾸되, 구현 방식이 아니라 필요한 능력과 판단 기준으로 쓴다.
5. 요구사항마다 사용자, 입력, 출력, 검수 기준, 사람 책임을 붙인다.
6. 접근 권한, 보안, 버전 관리처럼 팀 운영에서 먼저 합의해야 할 제약을 분리한다.
7. 하지 않을 것과 검증되지 않은 주장을 별도 경계로 적는다.
8. 2006-1-3 로드맵으로 넘길 우선순위 요구사항을 3개 이하로 고른다.

Source note (Zeude): steps 2-4 use only the Chapter 06 Zeude observation lenses from Primary source: `curriculum.md` row `2006-1-2`; Reference source: `lecture-decks.seed.yaml` reference repo `https://github.com/zep-us/zeude` and `decks/chapter-06.html` slides 8-11 notes; Guardrail: `decks/approved-source-checklist.md`.

## 작성 템플릿

```markdown
# 팀 AI 인프라 요구사항 명세

## 1. Scope
| 항목 | 내용 |
|---|---|
| 대상 팀 | [요구사항을 적용할 팀] |
| 공유할 하네스/스킬 | [팀에서 함께 쓸 규칙/스킬] |
| 해결할 운영 문제 | [공유/관측/동기화 중 막히는 점] |
| 최종 사용자 | [실제로 사용할 사람] |
| 성공기준 | [검수 가능한 완료 기준] |

## 2. Source Observation
| Zeude 관찰 관점 | 우리 팀 요구로 바꿀 질문 |
|---|---|
| 팀 공유 | 무엇을 팀 공통 기준으로 열람해야 하는가? |
| 모니터링 | 어떤 실행 상태와 실패 신호를 관측해야 하는가? |
| 동기화 | 설정, 규칙, 버전이 어디서 어긋나는가? |
| 빌더·랭킹 | 비개발자가 스킬을 만들고 사용량 랭킹에 오르게 어떻게 할 것인가? |
| 운영 기준 | 사용자가 어떤 상황에서 어떤 흐름을 따라야 하는가? |

## 3. Requirement Table
| 영역 | 요구사항 | 입력 | 출력 | 검수 기준 | 사람 책임 |
|---|---|---|---|---|---|
| 공유 | [무엇을 공유할지] | [원본 하네스/스킬] | [팀 공통 문서/목록] | [누가 봐도 같은 버전] | [소유자/승인자] |
| 관측 | [무엇을 볼지] | [실행 기록/상태] | [상태판/로그] | [실패 신호 확인 가능] | [판정 담당자] |
| 동기화 | [무엇을 맞출지] | [버전/설정/규칙] | [동기화 기준] | [불일치 확인 가능] | [변경 승인자] |
| 빌더·랭킹 | [누가 스킬을 만들고 어떻게 랭킹할지] | [비개발자 스킬 후보] | [사용량 랭킹/리더보드] | [쓰임이 드러난다] | [빌드 가이드/운영자] |
| 버전 관리 | [무엇을 관리할지] | [변경 대상] | [변경 기록] | [되돌릴 기준 존재] | [승인 담당자] |
| 사용 기준 | [사용자가 따를 흐름] | [사용 상황] | [체크리스트] | [혼자 실행 가능] | [교육/리뷰 담당자] |

## 4. Constraints and Boundaries
- 접근 권한/보안: [누가 볼 수 있고 수정할 수 있는가]
- 팀에서 먼저 합의할 규칙: [명명/버전/리뷰 기준]
- 지금 만들지 않을 것: [범위 밖 기능]
- 검증 없이 주장하지 않을 것: [성과 수치/자동화 효과 등]

## 5. Roadmap Handoff
| 우선순위 | 로드맵으로 넘길 요구사항 | 이유 |
|---|---|---|
| 1 | [가장 먼저 필요한 요구] | [운영 문제와 연결된 이유] |
| 2 | [다음 요구] | [의존성 또는 효과] |
| 3 | [선택 요구] | [나중으로 둬도 되는 이유] |
```

## 예시

Source note (Zeude): the example below is fictional learner work. Its Zeude-derived parts are limited to the source-backed lenses `팀 공유`, `모니터링`, `동기화`, and `운영 기준` from Primary source: `curriculum.md` row `2006-1-2`; Reference source: `decks/chapter-06.html` slides 8-11 notes; Guardrail: `decks/approved-source-checklist.md`.

```markdown
# 팀 AI 인프라 요구사항 명세

## 1. Scope
| 항목 | 내용 |
|---|---|
| 대상 팀 | 고객 운영팀 |
| 공유할 하네스/스킬 | 문의 답변 입력 요약표, 검수 체크리스트 |
| 해결할 운영 문제 | 사람마다 답변 검수 기준과 재질문 기준이 다르다 |
| 최종 사용자 | 답변 초안을 작성하고 검토하는 담당자 |
| 성공기준 | 같은 입력에서 누락 항목, 금지 표현, 남은 질문을 같은 방식으로 확인한다 |

## 2. Source Observation
| Zeude 관찰 관점 | 우리 팀 요구로 바꿀 질문 |
|---|---|
| 팀 공유 | 답변 하네스와 검수 기준을 어디서 공통으로 볼 것인가? |
| 모니터링 | 실행 기록에서 누락, 재시도, 재질문 신호를 어떻게 볼 것인가? |
| 동기화 | 정책 변경 시 템플릿과 체크리스트가 어디서 같이 바뀌는가? |
| 빌더·랭킹 | 담당자가 만든 답변 매크로/스킬을 어디서 모으고 많이 쓰인 것을 보여줄까? |
| 운영 기준 | 신규 담당자가 어떤 순서로 하네스를 써야 하는가? |

## 3. Requirement Table
| 영역 | 요구사항 | 입력 | 출력 | 검수 기준 | 사람 책임 |
|---|---|---|---|---|---|
| 공유 | 공통 하네스와 검수 기준을 한 곳에서 열람한다 | 최신 템플릿 | 공유 문서 | 담당자가 같은 버전을 본다 | 공개 범위 결정 |
| 관측 | 실행별 누락, 재시도, 재질문 사유를 남긴다 | 실행 결과 | 운영 기록 | 실패 사유가 다음 개선으로 이어진다 | 기록 해석 |
| 동기화 | 정책 변경이 템플릿에 반영됐는지 확인한다 | 변경된 정책 | 갱신된 체크리스트 | 오래된 기준이 남지 않는다 | 정책 승인 |
| 빌더·랭킹 | 담당자가 만든 답변 스킬을 모으고 사용량을 보여준다 | 담당자 제작 스킬 | 사용량 랭킹 | 많이 쓰인 스킬이 드러난다 | 큐레이션 담당 |
| 버전 관리 | 적용 버전과 변경 이유를 표시한다 | 변경 요청 | 버전 기록 | 누가 무엇을 바꿨는지 보인다 | 적용 승인 |
| 사용 기준 | 신규 담당자용 사용 순서를 제공한다 | 하네스 목록 | 사용 체크리스트 | 입력, 실행, 검수 순서가 보인다 | 예외 판단 |

## 4. Constraints and Boundaries
- 접근 권한/보안: 민감한 고객 정보는 공유 템플릿에 넣지 않는다.
- 팀에서 먼저 합의할 규칙: 최종 답변 발송 책임은 담당자에게 둔다.
- 지금 만들지 않을 것: 모든 업무를 한 번에 자동화하는 통합 시스템
- 검증 없이 주장하지 않을 것: 생산성 향상 폭, 비용 절감, 외부 사례 비교

## 5. Roadmap Handoff
| 우선순위 | 로드맵으로 넘길 요구사항 | 이유 |
|---|---|---|
| 1 | 공통 하네스와 검수 기준 공유 | 팀 기준을 먼저 맞춰야 한다 |
| 2 | 실패 사유 운영 기록 | 다음 개선 입력이 된다 |
| 3 | 변경 버전 기록 | 기준이 어긋나는 문제를 줄인다 |
```

## 완료 기준

- 대상 팀, 공유할 하네스/스킬, 해결할 운영 문제가 적혀 있다.
- Zeude 관찰은 공유·동기화, 모니터링·케어, 빌더·랭킹, 운영 기준 관점으로만 사용했다.
- 요구사항마다 입력, 출력, 검수 기준, 사람 책임이 있다.
- 접근 권한, 보안, 버전 관리 같은 운영 제약이 분리되어 있다.
- 지금 만들지 않을 것과 검증 없이 주장하지 않을 것이 명시되어 있다.
- 2006-1-3 로드맵으로 넘길 우선순위 요구사항이 3개 이하로 정리되어 있다.

## 제출/검토 체크리스트

- [ ] 산출물 이름은 팀 AI 인프라 요구사항 명세이다.
- [ ] Chapter 06 deck의 Zeude 관찰 범위를 벗어난 기능이나 효과를 추가하지 않았다.
- [ ] 구현 세부보다 팀 운영에 필요한 능력과 판단 기준으로 요구사항을 썼다.
- [ ] 공유, 관측, 동기화, 버전 관리, 사용 기준 중 필요한 영역이 표시되어 있다.
- [ ] 사람의 최종 판단과 승인 책임이 남아 있다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
