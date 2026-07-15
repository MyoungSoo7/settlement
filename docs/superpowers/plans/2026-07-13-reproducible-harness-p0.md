# Reproducible Harness P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 저장소 하네스, 정책 가드, 자기진단, hook 설치와 CI를 fresh clone에서도 재현되고 실행형 테스트로 검증되는 P0 기반으로 승격한다.

**Architecture:** `scripts/harness/manifest.json`을 필수 산출물과 Claude/Codex 핵심 계약의 정본으로 둔다. `guard.mjs`와 `harness-audit.mjs`는 import 가능한 순수 함수와 얇은 CLI adapter로 구성하고, Node 내장 test runner가 단위 fixture와 임시 Git 저장소 통합 fixture를 실행한다.

**Tech Stack:** Node.js 22 ESM, `node:test`, Node 표준 라이브러리, Git CLI, POSIX pre-commit hook, GitHub Actions.

## Global Constraints

- Node 22와 Node 내장 test runner 외 런타임 의존성을 추가하지 않는다.
- 상태 머신, Seed/Ontology 런타임 스키마, lock/resume/cancel은 P1/P2로 남긴다.
- manifest는 저장소 상대 POSIX 경로만 허용한다.
- 종료 코드는 `0=성공`, `1=정책/CLI/입력 실패`, `2=PreToolUse 차단`이다.
- malformed/unreadable 입력은 fail-closed로 처리하고 민감한 입력 본문을 출력하지 않는다.
- 관련 없는 `pwc/**`는 hash 측정 외에 수정, staging, commit하지 않는다.
- 모든 동작 변경은 실패 테스트를 먼저 실행한다.

---

### Task 1: Guard 정책 특성화와 구조화 예외

**Files:**
- Create: `scripts/harness/test/guard.test.mjs`
- Modify: `scripts/harness/guard.mjs`

**Interfaces:**

```js
export function parseAllowance(line, { now = new Date() } = {})
export function scanText(filePath, content, { now = new Date() } = {})
// scanText => { violations: [...], allowances: [...] }
```

- [ ] 현재 dirty `pwc/**` 경로와 SHA-256을 `$env:TEMP/settlement-p0-pwc-baseline.json`에 기록한다.
- [ ] `MONEY-PRIMITIVE`, `IMMUTABLE-HISTORY`, `MSA-BOUNDARY`, `ACCOUNT-CONSUME-ONLY`, `MARKET-NO-VALUATION`, `NO-COMMIT`의 위반/정상 fixture를 작성한다.
- [ ] bare/malformed/expired allowance 거부와 유효 allowance의 단일 줄 억제 테스트를 작성한다.
- [ ] `node --test scripts/harness/test/guard.test.mjs`가 export 누락과 bare allowance 허용 때문에 실패하는지 확인한다.
- [ ] 아래 검증식을 적용해 최소 구현한다.

```js
const ISSUE = /^(ISSUE-\d+|https:\/\/github\.com\/[^/\s]+\/[^/\s]+\/issues\/\d+)$/;
const OWNER = /^team-[a-z0-9]+(?:-[a-z0-9]+)*$/;
```

`reason`은 nonblank, `expires`는 실제 `YYYY-MM-DD`이며 `now`보다 미래여야 한다. 잘못된 marker는 `INVALID-ALLOWANCE` 위반을 추가한다.

- [ ] guard 테스트 전체가 통과하는지 확인한다.
- [ ] `scripts/harness/guard.mjs`, `scripts/harness/test/guard.test.mjs`만 커밋한다: `test(harness): execute guard policy fixtures`.

---

### Task 2: Fail-closed 입력과 pending 전체 내용 재구성

**Files:**
- Modify: `scripts/harness/guard.mjs`
- Modify: `scripts/harness/test/guard.test.mjs`

**Interfaces:**

```js
export async function normalizeRepoPath(repoRoot, requestedPath)
export async function readUtf8Strict(filePath)
export async function reconstructPendingContent(event, { repoRoot })
export function discoverStagedFiles(repoRoot)
export async function runGuardCli(args, io = {})
```

- [ ] Write 전체 content, Edit 정확히 1회 교체, MultiEdit 순차 교체 테스트를 작성한다.
- [ ] 0회/복수 일치, 빈 edits, 중간 단계 실패, 삭제/rename, root 밖 경로, symlink escape, invalid UTF-8 테스트를 작성한다.
- [ ] malformed hook, missing list/files, Git 외부 staged 실행, 충돌 CLI mode의 프로세스 종료 코드 테스트를 작성한다.
- [ ] focused test가 현재 fragment scan/fail-open 때문에 실패하는지 확인한다.

```powershell
node --test --test-name-pattern="Write|Edit|MultiEdit|UTF-8|hook|list|staged" scripts/harness/test/guard.test.mjs
```

- [ ] `TextDecoder('utf-8', { fatal: true })`로 strict decode한다.
- [ ] realpath와 nearest existing ancestor를 검사해 repo 밖 및 symlink/junction escape를 거부한다.
- [ ] staged 탐색은 `git diff --cached --name-only -z -M --diff-filter=ACMR`을 사용한다.
- [ ] `--staged`, `--list`, `--files`, `--hook`, `--self-test` 중 정확히 하나만 허용하는 dispatcher를 구현한다.
- [ ] `--self-test`는 동일 `guard.test.mjs`를 child process로 실행한다.
- [ ] 전체 테스트, malformed hook exit 2, missing list exit 1, self-test exit 0을 확인한다.
- [ ] 두 guard 파일만 커밋한다: `feat(harness): fail closed on invalid guard input`.

---

### Task 3: Manifest 기반 audit

**Files:**
- Create: `scripts/harness/manifest.json`
- Create: `scripts/harness/test/audit.test.mjs`
- Modify: `scripts/harness/harness-audit.mjs`

**Interfaces:**

```js
export function validateManifest(value)
export function extractHarnessContract(markdown)
export function readContractCases(json)
export function collectAudit(repoRoot, manifest)
export async function runAuditCli(args, io = {})
```

- [ ] invalid version, duplicate, absolute path, `..`, backslash, 계약 파일 누락 manifest 테스트를 작성한다.
- [ ] 임시 Git repo에서 tracked 성공, untracked/missing 실패, tracked-only inventory 테스트를 작성한다.
- [ ] command-script, settings-hook 파손과 STATUS 4개 claim의 일치/불일치 테스트를 작성한다.
- [ ] `harness-contract` 블록 없음/중복/invalid JSON/facts mismatch와 6개 contract case 누락 테스트를 작성한다.
- [ ] `node --test scripts/harness/test/audit.test.mjs`가 manifest와 export 부재로 실패하는지 확인한다.
- [ ] 승인된 설계의 `schemaVersion: 1`, required files, contract facts로 manifest를 만든다.
- [ ] `git -C <root> ls-files -z`만 inventory/tracking 정본으로 사용한다.
- [ ] Markdown prose 추론 없이 정확히 하나의 JSON block과 manifest facts를 deep-equal 비교한다.
- [ ] audit 테스트 전체가 통과하는지 확인한다.
- [ ] manifest, audit, audit test만 커밋한다: `feat(harness): audit required tracked artifacts`.

---

### Task 4: OS 독립 멱등 hook 설치

**Files:**
- Create: `scripts/harness/install-hooks.mjs`
- Create: `scripts/harness/test/install.test.mjs`
- Modify: `scripts/harness/hooks/pre-commit`
- Delete: `scripts/harness/install-hooks.sh`
- Modify: `.claude/commands/harness-check.md`
- Modify: `HARNESS.md`

**Interfaces:**

```js
export function findGitRoot(cwd)
export async function installHooks({ cwd = process.cwd(), stdout, stderr } = {})
```

- [ ] 임시 Git repo에서 installer 2회 실행, `core.hooksPath` 동일성, 파일 미생성 테스트를 작성한다.
- [ ] clean commit 허용, `pwc/blocked.txt` commit 거부, plugin 부재 독립 동작 테스트를 작성한다.
- [ ] `node --test scripts/harness/test/install.test.mjs`가 installer 부재로 실패하는지 확인한다.
- [ ] `git -C <cwd> rev-parse --show-toplevel`과 `git -C <root> config core.hooksPath scripts/harness/hooks`로 구현한다.
- [ ] pre-commit은 저장소 guard 실패와 존재하는 plugin guard 실패를 삼키지 않는다.
- [ ] 문서의 shell installer 참조를 Node installer로 교체한다.
- [ ] install/guard 테스트를 실행한다.
- [ ] 이 Task 파일만 커밋한다: `feat(harness): install tracked hooks idempotently`.

---

### Task 5: Claude/Codex 핵심 계약과 STATUS 정렬

**Files:**
- Modify: `.claude/skills/interview-harness/SKILL.md`
- Modify: `.codex/skills/interview-harness/SKILL.md`
- Modify: `.claude/skills/interview-harness/test_cases.json`
- Modify: `.codex/skills/interview-harness/test_cases.json`
- Modify: `STATUS.md`
- Modify: `scripts/harness/test/audit.test.mjs`

- [ ] 실제 repository audit가 현재 scratch/cycle/contract 차이를 잡는 실패 테스트를 작성한다.
- [ ] 양쪽 SKILL을 `.symposium/scratch/socrates.md`, 1..5 cycle, cycle 5 safety valve, user approval, boundary confirmation, Jaccard, 0.85로 정렬한다.
- [ ] manifest facts와 deep-equal인 `harness-contract` JSON fenced block을 양쪽에 정확히 하나 추가한다.
- [ ] 양쪽 test case에 다음 ID/transition을 동일하게 추가한다.

```json
[
  {"contractCase":"seed-gate-create","expectedTransition":"incomplete-seed->socrates"},
  {"contractCase":"seed-gate-reuse","expectedTransition":"complete-seed->evolve-step"},
  {"contractCase":"user-adoption","expectedTransition":"candidate-requires-explicit-user-approval"},
  {"contractCase":"first-cycle-skip","expectedTransition":"cycle-1->skip-comparison"},
  {"contractCase":"threshold-boundary","expectedTransition":"similarity>=0.85->convergence"},
  {"contractCase":"safety-cycle-5","expectedTransition":"cycle-5-not-converged->safety_valve"}
]
```

- [ ] `git ls-files` 기반 application/migration/ADR/test 수치를 다시 측정해 `STATUS.md`와 날짜를 갱신한다.
- [ ] audit test와 실제 audit를 실행한다.
- [ ] 이 Task 경로만 커밋한다: `fix(harness): align interview contract facts`.

---

### Task 6: 결정적 CI와 로컬 hook 배선

**Files:**
- Modify: `.github/workflows/harness-guard.yml`
- Modify: `.claude/settings.json`
- Modify: `scripts/harness/test/audit.test.mjs`
- Modify: `scripts/harness/manifest.json`

- [ ] workflow에서 tests → changed guard → audit → tracking → clean-diff 순서를 검사하는 실패 테스트를 작성한다.
- [ ] `fetch-depth: 0`, Node 22, `ACMR`, fallback 부재, PR fetch/merge-base, zero-SHA full scan을 검사한다.
- [ ] focused workflow test가 현재 workflow 때문에 실패하는지 확인한다.
- [ ] PR은 base ref fetch와 검증된 merge-base만 사용한다.
- [ ] push는 유효한 before commit만 사용하고 zero SHA는 전체 `git ls-files`, 나머지는 실패시킨다.
- [ ] 테스트, changed guard, audit, manifest tracking, `git diff --exit-code` 순서로 workflow를 구성한다.
- [ ] `.claude/settings.json`의 필수 repo hook을 유지하고 manifest legacy installer 경로를 제거한다.
- [ ] audit test와 실제 audit를 실행한다.
- [ ] 이 Task 파일만 커밋한다: `ci(harness): verify reproducible harness artifacts`.

---

### Task 7: Fresh-repository 통합 증명

**Files:**
- Modify: `scripts/harness/test/install.test.mjs`
- Modify when required by demonstrated failure: `scripts/harness/test/audit.test.mjs`, `scripts/harness/manifest.json`

- [ ] 임시 repo에 최종 manifest 파일을 만들고 모두 커밋하는 end-to-end 테스트를 먼저 작성한다.
- [ ] installer 2회, 전체 tests, guard self-test, audit, manifest tracking, clean diff를 순서대로 실행하고 각 exit를 검사한다.
- [ ] plugin/MCP 경로가 없어도 성공하고, 필수 파일을 commit 후 새로 쓰면 untracked 실패하며, 참조 삭제가 audit 실패인지 검사한다.
- [ ] focused fresh-repo 테스트를 실행해 실제 통합 gap으로 실패하는지 확인한다.
- [ ] 증명된 gap만 수정하고 required tracking/fail-closed 검사를 약화하지 않는다.
- [ ] 전체 executable tests와 guard self-test를 실행한다.
- [ ] 통합 테스트 관련 파일만 커밋한다: `test(harness): prove fresh repository reproducibility`.

---

### Task 8: 최종 검증과 독립 승인

**Files:**
- Verify: `scripts/harness/manifest.json`의 모든 경로
- Preserve: 기존 관련 없는 변경, 특히 `pwc/**`

- [ ] 검증 직전 `git status`와 하네스 파일 SHA-256을 TEMP에 기록한다.
- [ ] 다음 인수 명령을 순서대로 실행한다.

```powershell
node --test scripts/harness/test/*.test.mjs
node scripts/harness/guard.mjs --self-test
node scripts/harness/harness-audit.mjs
git ls-files --error-unmatch scripts/harness/manifest.json
```

- [ ] malformed hook exit 2, missing list/file nonzero를 직접 probe한다.
- [ ] manifest의 모든 required path에 `git ls-files --error-unmatch`를 실행한다.
- [ ] 검증 전후 status/hash 차이가 없음을 확인한다.
- [ ] P0 시작 시 저장한 `pwc/**` SHA-256과 현재 값이 동일하고 P0 commit에 `pwc` 경로가 없음을 확인한다.
- [ ] 별도 code-reviewer가 path traversal, fail-open, tracking, CI history, 계약 오탐/미탐을 검토한다.
- [ ] 별도 verifier가 설계의 모든 완료 기준을 직접 실행한다.
- [ ] blocking finding은 별도 TDD 수정 커밋으로 처리하고 전체 인수를 재실행한다.

## Completion Evidence

| 요구사항 | 증거 |
|---|---|
| 필수 산출물 추적 | manifest loop와 `git ls-files --error-unmatch` |
| 미추적 파일 거부 | 임시 Git audit negative test |
| 실행형 테스트 | `node --test scripts/harness/test/*.test.mjs` |
| fail-closed | hook/list/files 직접 exit probe |
| 구조화 예외 | line-scope + JSONL guard test |
| fresh clone 재현 | 임시 repo end-to-end test |
| 설치 멱등성 | installer 2회 + `core.hooksPath` assertion |
| STATUS/계약 정렬 | 실제 repo audit |
| CI 결정성 | workflow contract test |
| 검증 read-only | before/after status/hash |
| 사용자 변경 보존 | `pwc/**` SHA-256 및 commit path audit |
| 독립 승인 | code-reviewer 및 verifier 보고 |
