---
name: ceo-engagement-cycle
description: 브리핑 전달 이후의 반복 컨설팅 사이클 관리자(하네스) — 권고 조치 이행 추적(4주) → 재진단 델타 → 후속 리포트 → 회고 환류 → 다음 사이클을 고객별 상태로 관리하고, 단계에 맞는 서브 스킬(engagement-followup/review/retro)을 호출한다. "지난번 브리핑 후속", "이행 점검", "재진단", "엔게이지먼트" 류 요청에 사용. 첫 진단 자체는 ceo-risk-recon / ceo-consulting-pipeline 이 담당.
---

# CEO Engagement Cycle — 반복 컨설팅 관리자

일회성 진단을 반복 컨설팅으로 잇는 관리자. **이 스킬은 직접 분석하지 않는다** —
상태를 읽고, 지금 할 단계를 판정하고, 서브 스킬을 호출하고, 게이트를 검수한다.
상태 전이·게이트·델타 계산은 전부 결정론 CLI(`bin/engagement-cycle.mjs`)가 수행하며,
스킬 층은 대화(이행 인터뷰·리포트 서술·회고)만 담당한다.

```text
브리핑 전달(기존 파이프라인) ─ init
  → follow-up  권고 조치 이행 추적 (만기 4주)   … engagement-followup
  → review     재진단 + 델타 + 후속 리포트      … engagement-review
  → retro      회고 → 임계값·프리셋·템플릿 환류  … engagement-retro
  → next-cycle 새 브리핑으로 cycle+1 (랄프)
```

## 실행 경로 규칙 (설치 환경 공통)

- Claude Code 플러그인: `node "${CLAUDE_PLUGIN_ROOT}/bin/engagement-cycle.mjs"`
- Codex 플러그인: skills/ 상위 폴더가 플러그인 루트 (`~/.codex/plugins/trusted-ceo-agent/bin/…`)
- 저장소 직접 사용: `node src/bin/engagement-cycle.mjs`

아래의 `<ROOT>` 를 위 규칙으로 치환한다. 엔게이지먼트 상태는 기본
`engagements/<고객슬러그>/engagement.json` (init 시 `--dir` 로 변경 가능).

## 호출 시 관리자 절차

1. **상태 브리핑**: `<ROOT>/bin/engagement-cycle.mjs status --engagement <폴더>` 를 실행해
   "고객 · cycle N · phase · 이행 만기(지났는지) · 액션별 상태"를 요약 보고한다.
   엔게이지먼트가 없으면: 기존 파이프라인 산출 폴더(briefing.md + diagnostic-packet.json)를
   확인하고 `init --from <산출폴더>` 로 개설부터 한다.
2. **만기 선점검**: followUpDue 가 지났는데 phase 가 delivered 면 follow-up 진입을 먼저 제안한다.
3. **단계 디스패치**: phase 에 맞는 서브 스킬을 Skill 도구로 호출한다.

| phase | 서브 스킬 | 게이트 (CLI advance 가 강제) |
|---|---|---|
| delivered → follow-up | engagement-followup | 없음 (시작 선언) |
| follow-up → review | engagement-review | **전 액션에 이행 노트 1건 이상** — 빈 추적 금지 |
| review → retro | engagement-retro | **델타 산출물(--delta-file) 존재** |
| retro → next-cycle | (관리자가 직접) | **회고 파일 + 새 사이클 브리핑 산출 폴더** |

4. **전이는 CLI 로만**: `advance --to <phase>` 가 게이트를 검증한다. 게이트 실패 exit 1 이면
   같은 단계에 머물고 부족분을 사용자에게 보고한다. **스킬이 engagement.json 을 직접 고치지 않는다.**

## 관리자 가드레일

- 한 호출에 여러 단계를 건너뛰지 않는다. 사용자가 단계를 지목하면 점프하되 게이트 경고를 명시.
- 델타의 "해소"를 "조치 덕분"으로 단정하지 않는다 — 인과 서술은 이행 노트와 대조한 가설로만.
- 새 사이클의 첫 진단(브리핑 생성)은 이 스킬 밖의 일이다: `ceo-consulting-pipeline` 을 먼저
  실행하고 그 산출 폴더를 next-cycle 의 `--from` 으로 넘긴다.
- 이 사이클의 모든 산출물도 투자자문이 아니라 CEO 경영 판단 보조 자료다.
