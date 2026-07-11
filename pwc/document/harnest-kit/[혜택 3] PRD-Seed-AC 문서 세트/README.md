# PRD / Seed / AC 문서 세트

## 한 줄

How를 시작하기 전에 What을 먼저 잠근다. 무엇을 만들지(PRD)와 무엇을 충족해야 끝인지(AC)를 한 묶음으로 고정해 두면, AI가 매번 같은 계약에서 출발한다.

## 이 폴더의 자료

- `prd-seed-ac.template.md` — 한 업무를 PRD → Seed → AC Tree 세 칸으로 잠그는 빈 템플릿.
- `작성-가이드.md` — 세 칸에 무엇이 들어가고 무엇이 들어가면 안 되는지. What과 How를 가르는 기준.
- `예시-마케팅-ROI요약.md` — 다 채워진 한 묶음 예시. 모호한 요청 한 줄을 PRD → Seed v1 → AC Tree로 잠근 사례.
- `What-vs-How-체크리스트.md` — 작성한 문서에 How가 새어 들어왔는지 잡는 점검표.

## 어디서 쓰는가

Chapter 2 [Define — 공감에서 한 줄로 모이기]에서 만든 Seed(goal + constraints + acceptance_criteria)를 한 단계 더 문서로 고정하는 자리다. Chapter 3 [문서형 하네스]의 "How 전에 What을 잠근다" 척추와 Chapter 4 [AC Tree]로 이어진다.

curriculum.md 기준 핵심 메시지: **How 전에 What을 잠그고 완료조건을 고정한다.**

## PRD · Seed · AC 세 칸의 역할

| 칸 | 잠그는 것 | 한 줄 |
|---|---|---|
| **PRD** | 무엇을 / 왜 | 만들 대상과 이유를 적는다. 구현 방법(How)은 적지 않는다. |
| **Seed** | 고정 계약 | goal · constraints · acceptance_criteria 세 슬롯. 실행 전에 채워야 다음 단계로 넘어간다. |
| **AC Tree** | 완료조건 | "끝났다"를 작은 통과 조건으로 쪼갠다. 각 조건은 사람이 매번 안 봐도 판정 가능해야 한다. |

PRD는 방향, Seed는 계약, AC Tree는 판정 기준이다. 세 칸이 한 묶음으로 잠겨 있어야 [Evaluator(가이드 06)]가 "통과/재작업"을 결정론으로 가를 수 있다.

## 사용 순서

1. `작성-가이드.md`로 세 칸의 경계와 What/How 기준을 머릿속에 정렬한다.
2. `예시-마케팅-ROI요약.md`로 채워진 한 묶음의 모양을 본다.
3. `prd-seed-ac.template.md`를 본인 업무 하나로 옮겨 PRD → Seed → AC Tree를 채운다.
4. `What-vs-How-체크리스트.md`로 How가 새어 들어왔는지 점검한다.
5. 같은 Seed를 두 사람(또는 본인 + AI)에게 "이걸로 무엇을 만들지" 다시 진술하게 해, 같은 What이 나오는지 확인한다(자가 검증).

## 완료 기준

- PRD에 만들 대상과 이유가 적혀 있고, 구현 방법(How)이 섞여 있지 않다.
- Seed의 세 슬롯(goal · constraints · acceptance_criteria)이 모두 채워져 있다.
- AC Tree의 각 완료조건이 통과/실패로 판정 가능한 문장이다("좋다 / 깔끔하다" 같은 주관어가 아니다).
- 같은 Seed를 다시 진술했을 때 같은 What이 나온다(재현성).

## 원칙 — 한 줄

**What이 잠기지 않으면 완료조건도 매번 달라진다.** AI가 빈칸을 자기 사전으로 채우기 전에, 무엇을 만들지와 무엇을 충족해야 끝인지를 먼저 문서로 고정한다.
