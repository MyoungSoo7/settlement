# 2003-4-3. 프로젝트 1 산출물: 회의록 → 1page 기획안 하네스 워크스페이스

## 이 산출물
앞 산출물(1-3 체크리스트, 2-3 md 템플릿, 3-3 한계 진단표, 4-1 작성 프랙티스, 4-2 설계도)을 하나의 **Claude Code 스킬 워크스페이스**로 묶은 결과물이다. 회의록을 넣으면 같은 형식 1page 기획안 초안이 매번 같이 나오도록 SKILL.md(frontmatter 포함)·PostToolUse hook·hook 설정을 한 폴더에 정렬한다. 3장 실습 사슬의 마지막 산출물이다.

## 목적

이전 실습은 "md 규칙서 한 장"을 만드는 일이었다. 본 실습은 그 규칙서를 **Claude Code가 자동으로 읽고 실행 검사까지 돌리는 스킬**로 정렬한다. 4-1에서 그어 둔 "옮길 수 있는 항목(skills · hooks · using-* 메타)"을 본인 프로젝트로 옮기는 마지막 단계.

## 권장 시간

40분

## 준비물

- 2003-1-3 문서 하네스 체크리스트 (역할·금지·출력 형식)
- 2003-2-3 md 템플릿 v1 (본문 골격)
- 2003-3-3 문서형 하네스 한계 진단표 (적용 범위)
- 2003-4-1 하네스 작성 프랙티스 (권한·제약·순서·검수 + Superpowers 관찰)
- 2003-4-2 문서형 하네스 설계도 (입력 분해·출력 맵핑·hook 정의)
- 본인 회의록 원문 1건 (시운전용)
- Claude Code 설치 환경 (`../../../../.claude/settings.json`이 인식되는 프로젝트 루트)

## 산출물 구조

본 실습이 끝나면 다음 폴더 구조가 만들어져 있다.

```
<프로젝트-루트>/
└── .claude/
    ├── settings.json                          ← SessionStart + PostToolUse hook 트리거 설정
    ├── hooks/
    │   ├── session-start.sh                   ← 세션 시작 시 핵심 규약을 컨텍스트 상단에 주입
    │   └── check-1page-output.sh              ← Write/Edit 직후 산출물 가드 (3종 검사)
    └── skills/
        ├── using-<프로젝트>-harness/
        │   └── SKILL.md                       ← 메타 스킬 (Superpowers using-* 패턴) — hook 협업 규약·재작성 행동 양식
        └── <도메인>-to-1page/
            └── SKILL.md                       ← 도메인 스킬 — 보존 표현·출력 7칸 스키마·처리 순서·예시
examples/
└── sample-meeting.md                          ← 시운전용 회의록 1건 (선택)
```

여섯 파일이 모두 채워져야 산출물 완성. Superpowers에서 옮긴 네 가지 구성이 다음과 같이 매칭된다.

| Superpowers 구성 | 본 workspace | 역할 |
|---|---|---|
| `../../../../AGENTS.md` / `../../../../CLAUDE.md` 류 초기 지침 | SessionStart hook + 메타 스킬 본문 | 사전 안내 |
| `skills/<도메인>/SKILL.md` (도메인 스킬) | `skills/<도메인>-to-1page/SKILL.md` | 도메인 작업 규칙 |
| `hooks/` 가드 | `.claude/hooks/check-1page-output.sh` | 사후 검수 |
| `skills/using-superpowers/SKILL.md` (메타 스킬) | `skills/using-<프로젝트>-harness/SKILL.md` | 협업 규약 |

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 메타 스킬 이름 (kebab-case, `using-` 접두사) | 예: `using-1page-harness` |
| 도메인 스킬 이름 (kebab-case) | 예: `meeting-to-1page` |
| 도메인 스킬 description (triggers 포함) | "Use when ... Triggers on ..." 한 문장 |
| 회의록 원문 (시운전용) |  |
| 반드시 보존할 표현 (n줄) |  |
| 비어 있으면 질문할 항목 |  |
| 출력 형식 (1page 칸 이름들) |  |
| 사람이 판단할 영역 (스킬 범위 밖) |  |
| SessionStart hook이 안내할 규약 (n줄) |  |
| PostToolUse hook이 자동 검사할 항목 (n종) |  |

## AI에게 시키기

위 입력표를 채우고 앞 산출물(1-3·2-3·3-3·4-1·4-2)을 함께 붙인 뒤, 다음 한 줄을 덧붙인다.

> 이 입력으로 프로젝트 1 스킬 워크스페이스를 만들어줘. SKILL.md frontmatter에 name과 description(트리거 포함)을 정확히 적고, 본문은 4-1 권한·4-2 처리 순서·hook 협업 규약을 하나의 SKILL.md에 모아줘. .claude/hooks/check-1page-output.sh는 4-1 workspace의 검사 3종을 본인 보존 표현으로 갈아끼워 만들어줘. settings.json은 PostToolUse(Write|Edit) 트리거. 회의록에 없는 사실은 추가하지 말고 확인 필요 항목으로 남겨.

## 단계별 진행

1. **프로젝트 루트에 `../../../../.claude` 폴더 정렬** — `mkdir -p .claude/skills/using-<프로젝트>-harness/ .claude/skills/<도메인>-to-1page/ .claude/hooks/` 한 줄로 비어 있는 폴더를 먼저 만든다.
2. **메타 스킬 SKILL.md 작성** — `using-<프로젝트>-harness/SKILL.md`. frontmatter + 본문에는 hook 협업 규약·재작성 행동 양식·도메인 스킬 호출 순서만 적는다. 도메인별 보존 표현·출력 스키마는 여기에 적지 않는다.
3. **도메인 스킬 SKILL.md 작성** — `<도메인>-to-1page/SKILL.md`. 본인 도메인 보존 표현 n줄, 출력 7칸 스키마, 처리 순서, 예시 입력/출력을 named 섹션으로. 4-1·4-2 산출물의 도메인별 내용이 여기에 들어간다.
4. **SessionStart hook 작성** — `.claude/hooks/session-start.sh`에 본인 보존 표현과 핵심 규약을 stdout으로 출력하는 짧은 bash 스크립트. 세션이 시작될 때마다 Claude의 컨텍스트 상단에 주입된다. `chmod +x` 부여.
5. **PostToolUse hook 작성** — 4-1 workspace의 `check-1page-output.sh`를 본인 보존 표현 n줄로 갈아끼우고 `chmod +x` 부여.
6. **settings.json 작성** — `SessionStart` 트리거와 `PostToolUse(matcher: "Write|Edit")` 트리거 둘 다 등록.
7. **시운전** — 본인 회의록 1건을 Claude Code에 넣고 1page 초안 파일을 만들게 한다. 파일명에 `1page` 또는 `one-page`가 들어가야 PostToolUse hook이 트리거된다. SessionStart hook은 세션 시작 시 자동 실행.
8. **검수** — SessionStart 안내가 컨텍스트에 들어갔는지, PostToolUse hook이 통과(exit 0) 또는 실패 시 Claude가 부분만 보정해 같은 파일을 다시 쓰는지 확인.

## SKILL.md 작성 템플릿 — 메타 스킬

```markdown
---
name: using-<프로젝트>-harness
description: Meta skill for the <프로젝트> harness workspace. Defines hook contract, session-start protocol, and recovery behavior. Domain-specific rules live in sibling skills. Triggers on "<트리거1>", "<트리거2>".
---

# Using <프로젝트> Harness (메타 스킬)

## 두 스킬의 역할 분담
- 메타 (이 파일): hook 협업·재작성 루프·세션 시작 안내
- 도메인 (sibling): 보존 표현·출력 스키마·처리 순서·예시

## Hook 협업 규약
SessionStart — 세션 시작 시 핵심 규약을 컨텍스트 상단에 주입
PostToolUse — Write/Edit 직후 산출물 가드. 파일명 필터: 1page / one-page / <프로젝트 식별자>

## Hook 실패 시 행동 양식
1. 메시지에 적힌 위반 항목을 한 줄씩 읽는다.
2. 위반된 부분만 정확히 보정해 같은 파일을 다시 Edit. 전체 재작성 금지.
3. 두 번 연속 실패 시 멈추고 사용자에게 보고.

## 등록된 도메인 스킬
- <도메인>-to-1page: 회의록 → 1page 변환

## 못 잡는 항목 (4장 분업형 / MCP형 후보)
- 회차별 답변 추적
- 자동 재시도 횟수 제어
- 입력별 회귀 케이스
```

## SKILL.md 작성 템플릿 — 도메인 스킬

```markdown
---
name: <도메인>-to-1page
description: Convert <입력 종류> into a 1page draft with 7 sections. Domain skill — defines 보존 표현, output schema, processing order, and worked example. Triggers on "<트리거1>", "<트리거2>".
---

# <도메인> → 1page Domain Skill

## 역할
- 너의 역할:
- 책임 범위:
- 책임지지 않을 범위:

## 입력 명세
- 원문 종류:
- 확인된 요구사항:
- 반드시 보존할 표현 n줄 (큰따옴표 인용 유지):
- 비어 있으면 질문할 항목:

## 도메인 제약
- 입력에 없는 사실은 결론처럼 쓰지 않는다.
- 미결정 항목을 결론 톤("~로 한다")으로 단정하지 않는다.
- (도메인 특화 금지 항목 — 회의에서 범위 밖으로 합의된 항목)

## 처리 순서
1. 원문 보존
2. 확정 / 미결정 분리
3. 6칸 맵핑
4. 미결정은 확인 필요 항목 칸으로
5. 보존 표현 인용 검사
6. 한 페이지 마감

## 출력 형식 — 1page 7칸
제목 / 배경 / 문제 / 사용자 / 해결 방향 / 성공 기준 / 확인 필요 항목

## 메타 스킬과의 협업
본 도메인 스킬은 `using-<프로젝트>-harness` 메타 스킬의 협업 규약 아래에서 동작한다. hook 처리·재작성 행동 양식은 메타 스킬에서 정의.

## 예시 입력 / 출력
- 예시 입력:
- 예시 출력 (7칸 채움):
```

## hook 스크립트 템플릿 (`.claude/hooks/check-1page-output.sh`)

4-1 workspace의 `check-1page-output.sh`를 본인 보존 표현으로 갈아끼운다. 핵심 변경 부분:

```bash
PHRASES=(
  '"<본인 보존 표현 1>"'
  '"<본인 보존 표현 2>"'
  '"<본인 보존 표현 3>"'
)

# 파일명 필터 — 1page 산출물처럼 보일 때만 검사
case "$FILE_PATH" in
  *1page*|*one-page*|*<본인 프로젝트 식별자>*)
    ;;
  *)
    exit 0
    ;;
esac
```

미확인 수치 단정 검사(`60%` 같은 행)는 본인 회의록에 등장하는 단서가 붙은 수치로 갈아끼운다. 단서가 없으면 이 검사는 빼도 된다 — hook은 "있는 검사가 통과하는지"만 봄.

`chmod +x .claude/hooks/check-1page-output.sh` 잊지 말 것.

## SessionStart hook 템플릿 (`.claude/hooks/session-start.sh`)

세션이 시작될 때마다 stdout으로 적은 텍스트가 Claude의 system context로 주입된다. 4-1 관찰 메모의 `hooks/session-start`(시작 시 환경 고정) 자리를 그대로 옮긴 형태.

```bash
#!/usr/bin/env bash
cat <<'EOF'
[1page 하네스 워크스페이스 — 세션 시작 안내]

본 workspace에서는 회의록을 1page 기획안 초안으로 옮길 때 다음 메타 스킬을 매 호출 직전에 읽고 시작한다.
  .claude/skills/using-<프로젝트>-harness/SKILL.md

핵심 규약 (메타 스킬 본문에서 옮긴 요지):
  - 회의록과 확인된 요구사항 밖의 사실은 결론처럼 쓰지 않는다.
  - "반드시 보존할 표현" n줄은 큰따옴표 인용으로 유지한다.
  - 미결정 항목은 "확인 필요 항목" 섹션으로 분리한다.
  - 1page 산출물 파일명에는 1page / one-page 중 하나를 포함한다.
  - Write/Edit 직후 PostToolUse hook이 자동 검사. exit 2 메시지를 받으면 위반 부분만 보정.
EOF
```

`chmod +x .claude/hooks/session-start.sh`. 본인 보존 표현·핵심 규약을 본인 도메인 어휘로 갈아끼운다.

## settings.json 템플릿 (`../../../../.claude/settings.json`)

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/session-start.sh\""
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/check-1page-output.sh\""
          }
        ]
      }
    ]
  }
}
```

`$CLAUDE_PROJECT_DIR`은 Claude Code가 자동으로 주입하는 환경 변수. 본인 프로젝트 루트 기준 상대 경로로 동작한다. SessionStart는 세션 시작·`/clear` 직후, PostToolUse는 Write/Edit 직후 트리거된다.

## 예시 — 사내 교육 신청 페이지 개편 워크스페이스

4-1·4-2 workspace 입력을 4-3 형식으로 옮긴 사례.

### `.claude/skills/using-1page-harness/SKILL.md` (메타 스킬)

협업 규약·hook 동작·재작성 행동 양식만. 도메인 특화 내용은 sibling 도메인 스킬에 있다. 전체 예시는 `2003-4-1-workspace/.claude/skills/using-1page-harness/SKILL.md` 참고.

### `.claude/skills/meeting-to-1page/SKILL.md` (도메인 스킬)

```markdown
---
name: meeting-to-1page
description: Convert a meeting note into a 1page planning draft with 7 sections. Domain skill — defines 보존 표현, output schema, and worked example. Triggers on "회의록 정리", "1page 기획안", "회의록 → 1page", "사내 교육 신청 페이지 개편".
---

# Meeting → 1page Domain Skill

## 역할
- 너의 역할: 회의록 원문과 확인된 요구사항 안에서만 1page 기획안 초안을 7칸으로 구조화
- 책임 범위: 제목·배경·문제·사용자·해결 방향·성공 기준·확인 필요 항목
- 책임지지 않을 범위: 최종 일정·담당자 확정, 없는 사실 보강, 환불 기준 명문화

## 입력 명세
- 원문 종류: 회의록 원문
- 확인된 요구사항: (1) 신청 직전 핵심 정보 강조 화면, (2) 자동 안내 메일에 환불·일정 기준 추가
- 반드시 보존할 표현 (큰따옴표 인용 유지):
  - "신청 직후 환불·일정 문의를 줄인다"
  - "페이지 전체 리뉴얼은 이번 범위가 아니다"
  - "환불 기준 자체가 명문화돼 있지 않다"
- 비어 있으면 질문할 항목: 결정권자·일정·수치 출처·환불 기준 명문화

## 도메인 제약
- 회의록에 없는 수치·일정·담당자·외부 사례·환불 기준은 결론처럼 쓰지 않는다.
- "정확한 출처는 다시 뽑아 와야 한다" 단서 붙은 수치(CS 문의 60%)는 본문 결론이 아니라 확인 필요 항목에만.
- 페이지 전체 리뉴얼·결제 모듈 모달 위치 변경·환불 기준 명문화는 회의에서 범위 밖.

## 처리 순서
1. 회의록 원문 보존
2. 확정 / 미결정 두 묶음으로 분리
3. 확정 내용을 6칸으로 맵핑
4. 미결정을 확인 필요 항목 한 칸으로 모음
5. 보존 표현 3줄 인용 검사
6. 한 페이지 마감

## 출력 형식 — 1page 7칸
제목 · 배경 · 문제 · 사용자 또는 대상 · 해결 방향 · 성공 기준 · 확인 필요 항목

## 메타 스킬과의 협업
본 도메인 스킬은 `using-1page-harness` 메타 스킬 아래에서 동작한다. hook 처리·재작성 행동 양식은 메타 스킬 참조.

## 시운전 메모
- 첫 시운전 입력: 2026-05-22 사내 교육 신청 페이지 개편 회의록
- 첫 시운전 결과: 7칸 모두 채워짐. PostToolUse hook 1회 통과.
- 두 번째 시운전: 다른 회의록으로 — 결과 칸 구성 일치.
- v2에서 손볼 항목: 시안·메일 일정 외부 회신을 어떻게 추적할지
```

### `.claude/hooks/check-1page-output.sh`

본인 보존 표현 3줄로 갈아끼운 형태. 전체 본문은 4-1 workspace의 `check-1page-output.sh`와 같다.

### `../../../../.claude/settings.json`

위 템플릿 그대로.

## 완료 기준

- `.claude/skills/using-<프로젝트>-harness/SKILL.md` (메타 스킬)가 hook 협업 규약·재작성 행동 양식·도메인 스킬 호출 순서로 채워져 있다.
- `.claude/skills/<도메인>-to-1page/SKILL.md` (도메인 스킬)가 보존 표현·출력 7칸 스키마·처리 순서·예시로 채워져 있다.
- `.claude/hooks/session-start.sh`와 `.claude/hooks/check-1page-output.sh`가 실행 권한과 함께 있고, 본인 보존 표현으로 갈아끼워졌다.
- `../../../../.claude/settings.json`이 SessionStart hook과 PostToolUse hook 둘 다 연결한다.
- 세션을 새로 시작했을 때 SessionStart hook의 안내가 Claude 컨텍스트에 들어갔다.
- 본인 회의록 1건을 시운전했을 때 PostToolUse hook이 통과(exit 0) 또는 실패 시 Claude가 보정하는 흐름이 보였다.
- 회의록에 없는 내용은 확인 필요 항목 칸에만 남는다.
- 같은 형식 회의록을 두 번 넣었을 때 같은 칸 구성으로 결과가 나온다 (deterministic 한 줄 시험).

## 제출/검토 체크리스트

- [ ] 메타 스킬과 도메인 스킬이 분리된 두 SKILL.md로 존재한다 (Superpowers의 `using-superpowers` vs `writing-skills` 패턴과 같은 구조).
- [ ] 도메인 스킬에 1-3, 2-3, 3-3, 4-1, 4-2 산출물의 보존 표현·출력 스키마·처리 순서가 옮겨져 있다.
- [ ] 메타 스킬에는 도메인 특화 내용이 없고, hook 협업 규약과 재작성 행동 양식만 있다.
- [ ] 두 SKILL.md frontmatter의 description에 본인 도메인 트리거 단어가 들어가 있다.
- [ ] SessionStart hook이 본인 핵심 규약을 stdout으로 안내한다.
- [ ] PostToolUse hook 스크립트가 본인 보존 표현으로 갈아끼워졌다.
- [ ] 비개발자도 본 워크스페이스를 복사해서 본인 프로젝트 루트에 두면 동작한다.
- [ ] 확인 질문과 1page 기획안 초안이 분리되어 나온다.
- [ ] 시운전 결과에서 PostToolUse hook이 한 번 이상 동작했다 (통과든 실패든).
- [ ] 적용 범위 밖 항목(회차 추적·자동 재시도·외부 회신)이 4장 분업형/MCP형 후보로 표시되어 있다.
