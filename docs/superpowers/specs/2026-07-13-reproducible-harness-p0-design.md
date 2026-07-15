# 재현 가능한 하네스 P0 설계 (2026-07-13)

## 상태

- 목표: 로컬 정책 초안인 하네스를 저장소 자체에 포함되고 자동 검증되는 기반으로 승격한다.
- 범위: P0 재현성 기반만 다룬다.
- 결정: 기존 `guard.mjs`, `harness-audit.mjs`, Git hook, command, CI 구조를 보존하고 강화한다.
- 후속 단계: 실행형 인터뷰 상태 머신과 Seed/Ontology 런타임 스키마는 P1/P2에서 구현한다.

## 목표

1. fresh clone에 필수 하네스 산출물이 모두 포함된다.
2. 하나의 manifest가 필수 파일의 존재와 Git 추적 여부를 정의한다.
3. guard와 audit 로직을 테스트와 CLI 양쪽에서 실행할 수 있다.
4. 잘못되거나 읽을 수 없는 guard 입력은 fail-closed로 처리한다.
5. guard 예외에는 감사 가능한 사유와 이슈 식별자가 필요하다.
6. 선언형 `test_cases.json`을 실행형 Node 테스트로 보완한다.
7. CI가 추적 상태, 참조 무결성, 정책 집행, 자기진단을 증명한다.
8. Claude/Codex의 핵심 interview-harness 계약이 조용히 달라지지 않는다.
9. 설치는 저장소 위치와 무관하고 멱등적이다.
10. 관련 없는 `pwc/**` 변경은 수정하거나 스테이징하지 않는다.

## 비목표

- interview-harness 실행 상태 머신 구현
- Seed, Ontology, 실행 상태 스키마 도입
- 잠금, atomic 상태 저장, resume, cancel 구현
- 모든 정규식 규칙을 AST 또는 ArchUnit으로 교체
- 모든 Claude/Codex 자산을 단일 YAML에서 생성
- 기존 필수 가드 집합 밖의 도메인 규칙 재설계
- 사용자 소유의 관련 없는 변경 정리 또는 커밋

## 아키텍처

```text
scripts/harness/manifest.json
          |
          v
scripts/harness/harness-audit.mjs <---- .claude/commands/harness-check.md
          |
          +---- Git tracking / reference / STATUS drift
          +---- hook wiring / critical contract parity

staged files / CI changed files / Claude hook input
          |
          v
scripts/harness/guard.mjs
          |
          +---- importable pure functions
          +---- CLI adapter and fail-closed errors

scripts/harness/test/*.test.mjs
          |
          +---- unit fixtures
          +---- temporary Git repository integration fixtures

.github/workflows/harness-guard.yml
          |
          v
tests -> changed-file guard -> self-audit -> tracking/clean checks
```

Node 22와 Node 내장 test runner 외의 런타임 의존성은 추가하지 않는다.

## 파일 책임

| 파일 | 책임 |
|---|---|
| `scripts/harness/manifest.json` | 필수 Git 추적 파일과 Claude/Codex 핵심 계약 쌍의 단일 목록 |
| `scripts/harness/guard.mjs` | 순수 scan, 예외 검증, hook 파싱, staged 파일 탐색과 CLI 제공 |
| `scripts/harness/harness-audit.mjs` | manifest 추적, 참조, hook, STATUS, inventory, 계약 동등성 검증 |
| `scripts/harness/install-hooks.mjs` | Git으로 루트를 탐색하고 `core.hooksPath`를 OS 독립적으로 멱등 설정 |
| `scripts/harness/hooks/pre-commit` | 저장소 guard 호출 및 선택적 plugin guard 보조 호출 |
| `scripts/harness/test/guard.test.mjs` | 정책, 예외, 입력 오류, CLI 종료 동작 테스트 |
| `scripts/harness/test/audit.test.mjs` | manifest, Git 추적, 참조, drift, 계약 테스트 |
| `scripts/harness/test/install.test.mjs` | 임시 저장소 기반 설치 및 멱등성 테스트 |
| `.github/workflows/harness-guard.yml` | P0 필수 검증 파이프라인 |
| `.claude/settings.json` | Git 추적 guard로의 저장소 로컬 hook 배선 |
| `STATUS.md` | audit가 측정한 현재 프로젝트 수치 |

## Git Tracking Manifest

`manifest.json`은 실행 설정이 아닌 검증 데이터이며 저장소 상대 POSIX 경로만 허용한다.

```json
{
  "schemaVersion": 1,
  "requiredTrackedFiles": [
    "scripts/harness/manifest.json",
    "scripts/harness/guard.mjs",
    "scripts/harness/harness-audit.mjs",
    "scripts/harness/install-hooks.mjs",
    "scripts/harness/hooks/pre-commit",
    "scripts/harness/test/guard.test.mjs",
    "scripts/harness/test/audit.test.mjs",
    "scripts/harness/test/install.test.mjs",
    ".github/workflows/harness-guard.yml",
    ".claude/commands/harness-check.md",
    ".claude/settings.json",
    ".claude/skills/interview-harness/SKILL.md",
    ".claude/skills/interview-harness/test_cases.json",
    ".codex/skills/interview-harness/SKILL.md",
    ".codex/skills/interview-harness/test_cases.json",
    "STATUS.md"
  ],
  "criticalContractPairs": [
    {
      "claude": ".claude/skills/interview-harness/SKILL.md",
      "codex": ".codex/skills/interview-harness/SKILL.md",
      "contract": "interview-harness",
      "facts": {
        "seedGate.requiredFields": ["goal", "constraints", "acceptance_criteria"],
        "stageOrder": ["evolve-step", "ontology", "compare"],
        "similarity.idea": "exact",
        "similarity.boundary": "user-confirmed",
        "similarity.properties": "jaccard",
        "similarity.threshold": 0.85,
        "maxCycles": 5,
        "firstCycleComparison": "skip",
        "candidateAdoption": "explicit-user-approval",
        "canonicalScratch": ".symposium/scratch/socrates.md",
        "stopReasons": ["convergence", "safety_valve"]
      }
    },
    {
      "claude": ".claude/skills/interview-harness/test_cases.json",
      "codex": ".codex/skills/interview-harness/test_cases.json",
      "contract": "interview-harness-tests"
    }
  ]
}
```

검사 규칙:

- 모든 필수 항목은 존재하고 `git ls-files --error-unmatch`에 포함돼야 한다.
- 미추적 파일은 필수 산출물을 충족하지 않는다.
- 중복, 절대경로, `..`, 역슬래시, 미지원 `schemaVersion`은 실패한다.
- 핵심 계약 파일은 양쪽 모두 존재하고 추적돼야 한다.
- 각 SKILL에는 `harness-contract` fenced JSON 블록을 정확히 하나 둔다. audit는 Markdown 본문을 추론하지 않고 이 블록을 JSON으로 파싱한다.
- 양쪽 블록은 manifest의 `facts`와 deep-equal이어야 한다. 알 수 없는 필드, 중복 블록, JSON 파싱 실패도 차단한다.
- `test_cases.json`은 query 문구가 아니라 `contractCase` ID와 `expectedTransition`을 비교한다. 필수 ID는 `seed-gate-create`, `seed-gate-reuse`, `user-adoption`, `first-cycle-skip`, `threshold-boundary`, `safety-cycle-5`다.
- canonical scratch 경로는 `.symposium/scratch/socrates.md`, 최대 실행 횟수는 정확히 5회로 통일한다. `cycle > 5` 표현은 허용하지 않는다.

## Fail-Closed 동작

다음 입력 오류는 비정상 종료한다.

- 비어 있거나 잘못된 `--hook` JSON
- `tool_input` 또는 유효한 `file_path` 누락
- pending content를 안전하게 복원할 수 없는 hook 요청
- `--files`/`--list`로 선택한 파일을 읽지 못함
- `--list` 파일 누락
- staged 파일 탐색 실패
- 지원하지 않거나 충돌하는 CLI 모드

종료 코드는 `0=검증 성공`, `1=정책/CLI/입력 실패`, `2=Claude PreToolUse 차단`으로 고정한다. 오류 메시지는 실패한 작업을 나타내되 입력 본문이나 민감정보를 출력하지 않는다.

### Write/Edit/MultiEdit 재구성 계약

- `Write`: `content`가 새 파일 전체 내용이다. 문자열이 아니면 차단한다.
- `Edit`: 현재 파일을 UTF-8로 읽고 `old_string`이 정확히 한 번 존재할 때만 `new_string`으로 교체해 pending 전체 내용을 만든다.
- `MultiEdit`: 현재 파일에서 시작해 배열 순서대로 교체한다. 각 단계의 `old_string`은 그 시점 내용에 정확히 한 번 존재해야 한다.
- 0회 또는 복수 일치, 빈 edit 배열, 잘못된 필드 타입, 파일 누락, UTF-8 decode 실패는 exit 2다. UTF-8은 `TextDecoder('utf-8', { fatal: true })` 또는 동등한 strict decoder로 판정한다.
- `file_path`는 Git root 기준 실제 경로로 정규화한다. root 밖 경로와 symlink/junction escape는 exit 2다.
- 삭제와 rename처럼 최종 내용을 제공하지 않는 작업은 전용 명령 가드가 생기기 전까지 exit 2다.
- scan은 fragment가 아니라 재구성된 전체 pending 내용을 대상으로 수행한다.

fixture는 유일 교체 성공, 0/복수 일치 실패, 순차 MultiEdit 성공, 중간 단계 실패, root escape, symlink escape, 잘못된 UTF-8, 삭제/rename 거부를 포함한다.

예외는 다음 형식만 허용한다.

```text
harness-guard:allow reason="bounded technical reason" issue="ISSUE-123" owner="team-settlement" expires="2026-08-01"
```

- `reason`은 비어 있지 않아야 한다.
- `issue`는 `ISSUE-123` 또는 저장소 issue URL이어야 한다. ADR은 영구 우회 식별자로 허용하지 않는다.
- `owner`는 `team-` 접두사의 코드 소유 팀이어야 하고 `expires`는 유효한 미래 ISO 날짜여야 한다.
- 예외는 해당 줄의 위반만 억제한다.
- 잘못된 예외 문법은 그 자체로 차단 위반이다.
- 결과에는 파일, 줄, 사유, 이슈, 소유자, 만료일을 JSON Lines로 stdout에 출력한다. GitHub Actions workflow log와 pre-commit 로그가 이를 보존하며 P0에서는 별도 artifact 파일을 생성하지 않는다.
- `--no-verify`를 해결 방법으로 안내하지 않는다.

## 실행형 테스트

### Guard

- 각 현행 규칙의 위반 fixture와 정상 fixture
- Java/Kotlin 주석 제외 동작
- bare/malformed 예외 거부 및 구조화 예외 허용
- malformed/empty/incomplete hook 입력 거부
- 누락되거나 읽을 수 없는 선택 파일 거부
- staged 파일 탐색 실패 거부
- rename 목적지가 `hackathon/` 또는 `pwc/`인 경우 거부
- Windows/POSIX 경로 정규화
- hook 차단 exit 2 및 일반 실패 nonzero

### Audit

- 필수 파일 존재+추적 성공
- 존재하지만 미추적이면 실패
- 필수 파일 누락 및 잘못된 manifest 실패
- command-script와 settings-hook 참조 파손 실패
- STATUS 수치 불일치 실패
- Claude/Codex 핵심 계약 불일치 실패
- inventory는 Git 추적 파일만 집계

### 임시 Git 저장소 통합 테스트

1. 최소 fixture 저장소를 만들고 필수 파일을 커밋한다.
2. audit 성공을 확인한다.
3. 미추적 파일이 manifest를 충족하지 못함을 확인한다.
4. 참조 스크립트 제거 시 audit 실패를 확인한다.
5. `node scripts/harness/install-hooks.mjs`를 두 번 실행하고 양쪽 모두 성공함을 확인한다.
6. `core.hooksPath=scripts/harness/hooks`가 유지되는지 확인한다.
7. 위반 파일은 pre-commit이 거부하고 정상 파일은 허용하는지 확인한다.

테스트는 개발자의 현재 저장소 설정을 변경하지 않는다.

## CI 순서

1. 충분한 Git history로 checkout
2. Node 22 설치
3. `node --test scripts/harness/test/*.test.mjs`
4. 아래 규칙으로 changed-files base 검증 및 계산
5. `node scripts/harness/guard.mjs --list changed.txt`
6. `node scripts/harness/harness-audit.mjs`
7. manifest 전체에 대한 Git 추적 확인
8. `git diff --exit-code`로 검증 과정의 변경 생성 방지

`continue-on-error`를 사용하지 않는다. 파일, manifest, Git history, changed-file 목록 문제는 빈 검사로 통과하지 않고 실패한다.

- PR: `origin/<base_ref>`를 명시적으로 fetch하고 merge-base가 존재하는지 확인한 후 `<merge-base>...HEAD`를 사용한다.
- push: `github.event.before`가 40자리 nonzero SHA이고 `git cat-file -e <sha>^{commit}`에 성공하면 `<before>...HEAD`를 사용한다.
- 최초 push처럼 before가 zero SHA이면 보호 브랜치에서 전체 `git ls-files`를 검사한다.
- 그 밖의 SHA/history 오류는 fallback 없이 실패한다. 빈 changed 파일은 실제 diff가 빈 경우에만 허용한다.
- staged와 CI diff 모두 rename detection을 활성화하고 `--diff-filter=ACMR`을 사용한다. rename은 목적지 경로를 반드시 검사해 정상 경로에서 `hackathon/` 또는 `pwc/`로 이동하는 우회를 차단한다.
- 삭제(`D`)는 내용 guard가 아니라 manifest/reference audit가 담당한다. 필수 파일 또는 참조 대상 삭제는 audit 실패가 된다.

## 마이그레이션 순서

1. 관련 없는 변경, 특히 `pwc/**`의 before hash를 기록한다.
2. tracking, hook 입력, 예외, 참조, STATUS, 계약에 대한 실패 테스트를 먼저 추가한다.
3. manifest를 추가하고 guard/audit를 import 가능한 단위로 리팩터링한다.
4. 입력 및 파일 탐색 실패를 fail-closed로 바꾼다.
5. bare escape hatch를 구조화 예외로 교체한다.
6. `STATUS.md` 주장값을 Git 추적 기준 실측치로 맞춘다.
7. Claude/Codex의 핵심 계약 사실만 정렬한다.
8. CI를 정해진 순서로 갱신한다.
9. 로컬 인수 명령을 실행하고 별도 reviewer/verifier가 검토한다.
10. 모든 필수 P0 산출물을 함께 커밋해 중간 커밋이 재현성을 주장하지 않게 한다.

## 위험과 통제

| 위험 | 통제 |
|---|---|
| fail-closed가 정상 편집을 막음 | 명확한 진단, positive/negative fixture, 제한된 구조화 예외 |
| 정규식 오탐/미탐 | fixture 확대, 의미 기반 규칙은 P2 이후 ArchUnit/AST로 이동 |
| Claude/Codex의 정당한 표현 차이 | 원문 비교 대신 핵심 계약 사실 비교 |
| Windows/Linux Git 차이 | Node installer/subprocess, POSIX manifest 경로, Windows 로컬 테스트와 Ubuntu CI |
| 설치 테스트가 로컬 hook을 변경 | 임시 Git 저장소만 사용 |
| STATUS parser 취약성 | 지원하는 claim 패턴을 명시하고 각각 테스트 |
| first push에 base가 없음 | 검증된 merge-base fallback 또는 명시적 실패, 빈 목록 통과 금지 |

## 완료 기준

P0는 다음 조건이 현재 저장소의 증거로 모두 확인될 때 완료한다.

1. 모든 manifest 항목이 존재하고 Git 추적된다.
2. 미추적 파일은 필수 산출물을 충족하지 않는다.
3. `node --test scripts/harness/test/*.test.mjs`가 성공한다.
4. 명시한 unit/integration 사례가 실행형 테스트에 포함된다.
5. `node scripts/harness/guard.mjs --self-test` 또는 동일 fixture suite가 성공한다.
6. `node scripts/harness/harness-audit.mjs`가 성공한다.
7. malformed hook JSON은 exit 2를 반환한다.
8. 누락된 `--files`/`--list` 입력은 nonzero를 반환한다.
9. bare 예외는 실패하고 구조화 예외는 해당 줄만 억제하며 보고된다.
10. 임시 fresh repository에서 install 2회, tests, guard, audit가 plugin/MCP 없이 성공한다.
11. 두 번의 설치 후 `core.hooksPath`가 `scripts/harness/hooks`다.
12. command/script 참조 파손과 STATUS 불일치는 audit 실패를 만든다.
13. 현재 `STATUS.md`는 Git 추적 기준 실측값과 일치한다.
14. Claude/Codex 핵심 계약 불일치는 실패하고 현재 계약은 통과한다.
15. CI가 tests, changed guard, audit, tracking, clean-diff 순서로 실행된다.
16. 검증 과정이 새 저장소 변경을 만들지 않는다.
17. 기존 `pwc/**` 변경은 P0 전후 byte-for-byte 동일하다.
18. 별도 reviewer/verifier가 blocking 이슈 없음으로 판정한다.

로컬 인수 순서:

```powershell
node --test scripts/harness/test/*.test.mjs
node scripts/harness/guard.mjs --self-test
node scripts/harness/harness-audit.mjs
git ls-files --error-unmatch scripts/harness/manifest.json
```

저장소가 이미 dirty이므로 검증 명령 전후 path set과 content hash를 비교해 검증 과정이 새 변경을 만들지 않았음을 증명한다.
