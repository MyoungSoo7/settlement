---
name: using-1page-harness
description: Meta skill for the 1page planning harness workspace (2003-4-3). Defines hook contract, SessionStart protocol, and recovery behavior. Domain-specific rules live in sibling skills like meeting-to-1page. Triggers on "회의록 정리", "1page 기획안", "1page 하네스", "회의록 → 1page".
---

# Using 1page Harness (메타 스킬)

본 메타 스킬은 도메인 작업이 어떻게 작동해야 하는지를 정의한다. 회의록 → 1page 변환 같은 구체적 작업 규칙은 sibling 도메인 스킬에 분리되어 있다.

## 두 스킬의 역할 분담

| 스킬 | 역할 |
|---|---|
| 메타 (`using-1page-harness`, 이 파일) | 협업 규약. hook 동작, exit 2 대응, 세션 시작 안내, 도메인 스킬 호출 순서. |
| 도메인 (`meeting-to-1page` 등 sibling) | 구체적 작업 규칙. 보존 표현, 출력 7칸 스키마, 처리 순서, 예시 입력/출력. |

도메인이 추가되면(예: 인터뷰 → 1page, 이슈 → 1page) 새 도메인 스킬을 sibling으로 두고 본 메타 스킬은 손대지 않는다.

## Hook 협업 규약

본 workspace에 등록된 hook 두 개:

| Hook | 트리거 | 역할 |
|---|---|---|
| SessionStart (`.claude/hooks/session-start.sh`) | 세션 시작 또는 `/clear` 직후 | 핵심 규약을 stdout으로 출력 → system context 상단에 주입 |
| PostToolUse (`.claude/hooks/check-1page-output.sh`) | Write/Edit 직후 | 1page 산출물 파일 자동 검사. 위반 시 exit 2 + stderr |

PostToolUse 현재 검사 항목 3종:
1. 보존 표현 n줄이 큰따옴표 인용으로 살아 있는지
2. "확인 필요 항목" 섹션이 존재하는지
3. 미확인 수치가 결론 본문에 단정 형태로 들어갔는지

검사 항목은 도메인이 바뀔 때 도메인 스킬과 함께 갈아끼운다.

## 대상 파일 필터

hook은 파일 경로에 `1page`, `one-page`, `2003-4-3` 중 하나가 포함된 경우만 검사한다. 회의록 원문·중간 산출물·다른 도메인 파일은 건드리지 않는다. 1page 초안 파일명을 지을 때 위 키워드 중 하나를 포함시킨다.

## Hook 실패 시 행동 양식

PostToolUse hook에서 exit 2 메시지를 받으면 다음과 같이 행동한다.

1. 메시지에 적힌 위반 항목을 한 줄씩 읽는다.
2. 사용자에게 묻지 말고 위반된 부분만 정확히 보정해 같은 파일을 다시 Edit 한다.
3. 보정 시 전체 재작성 금지. 위반 위치만 손본다.
4. 같은 파일에 두 번 연속 hook 실패가 나면 멈추고 사용자에게 어디가 안 풀리는지 보고한다.

## 도메인 스킬 호출 순서

사용자가 회의록을 던지면:

1. 회의록의 도메인을 식별한다 (회의 결과 요약? 1page 기획안? 다른 형식?).
2. 매칭되는 도메인 스킬의 SKILL.md를 본 메타 스킬과 함께 읽는다.
3. 도메인 스킬의 처리 순서·출력 형식·보존 표현을 따라 작업한다.
4. 출력 파일명에 hook 트리거 키워드를 포함시켜 PostToolUse 검사가 자동으로 돌게 한다.

## 현재 등록된 도메인 스킬

- `meeting-to-1page` — 회의록 → 1page 기획안 7칸 변환 (사내 교육 신청 페이지 개편 같은 컨텍스트)

## 이 워크스페이스 + hook 으로도 못 잡는 항목 (4장 분업형 / MCP형 후보)

- `commands/` 호출 순서 강제 (hook은 결과만 본다)
- 확인 필요 항목 회차 추적 (월요일 미팅 → 운영팀 회신 → CS 수치 회신)
- 보존 표현 누락 시 자동 재시도 횟수 제어
- 입력별 회귀 케이스 (`tests/`)

만나면 사용자에게 4장 분업형/MCP형 검토가 필요하다고 알린다.

## 관련 파일

- 회의록 원문 (예시): `examples/sample-meeting.md`
- 도메인 스킬: `.claude/skills/meeting-to-1page/SKILL.md`
- SessionStart hook: `.claude/hooks/session-start.sh`
- PostToolUse hook: `.claude/hooks/check-1page-output.sh`
- hook 설정: `.claude/settings.json`
