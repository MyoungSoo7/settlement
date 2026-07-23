# Superpowers 플러그인 — 사용법과 자주 쓰는 기능

> Claude Code용 **프로세스 스킬 라이브러리** 플러그인(obra/Jesse Vincent, MIT). 도메인 지식이
> 아니라 "일하는 절차"(TDD·디버깅·계획·리뷰)를 스킬로 패키징하고, 세션마다 규율 문서를
> 주입해 스킬 사용을 강제한다. 이 저장소 기준 설치 버전 **v6.1.1**.
>
> **이 프로젝트에서의 위상·측정은 [`superpowers-harness.md`](./superpowers-harness.md) 가 정본** —
> 본 문서는 플러그인 자체의 사용법 소개다. **Last updated:** 2026-07-22

## 1. 설치·활성 관리

```bash
claude plugin install superpowers@superpowers-marketplace   # 설치 (마켓플레이스: obra/superpowers-marketplace)
claude plugin enable  superpowers@superpowers-marketplace   # 활성
claude plugin disable superpowers@superpowers-marketplace   # 비활성
claude plugin update  superpowers@superpowers-marketplace   # 업데이트 (적용은 새 세션부터)
claude plugin list                                          # 상태 확인
```

- 캐시 위치: `~/.claude/plugins/cache/superpowers-marketplace/superpowers/<버전>/`
- ⚠️ **이 저장소는 A/B 실험 중(2026-07-22~)**: 홀수일 ON / 짝수일 OFF day-parity 토글 —
  임의로 enable/disable 하지 말고 [`superpowers-harness.md`](./superpowers-harness.md) §4 규칙을 따를 것.

## 2. 동작 원리 (두 층)

1. **세션 주입** — SessionStart 훅(`startup|clear|compact` 시)이 `using-superpowers` 스킬 전문을
   `<EXTREMELY_IMPORTANT>` 태그로 컨텍스트에 주입한다. 내용: "1%라도 해당하면 스킬을 반드시
   호출", 합리화 차단 Red Flags 표, 프로세스 스킬 우선 원칙. `/clear`·compact 때마다 재주입돼
   긴 세션에서도 규율이 유지된다(v6부터 resume 시엔 재주입 없음).
2. **온디맨드 스킬 14종** — 나머지는 이름+설명만 노출되고, Skill 도구로 호출할 때 본문이
   로드된다(`superpowers:<스킬명>`). 호출 시 "Using [skill] to [purpose]" 선언 후 절차를 따른다.

**지침 우선순위**: 사용자 지침(CLAUDE.md 등) > superpowers 스킬 > 기본 동작 — v6 명문화.
이 저장소에선 TDD·디버깅·완료검증이 자체 스킬 정본이므로 해당 3종은 프로젝트 스킬이 이긴다
(HARNESS.md "이중 라우팅 경계").

## 3. 스킬 카탈로그 (v6.1.1, 14종)

| 스킬 | 언제 쓰나 |
|---|---|
| `using-superpowers` | (메타) 스킬 사용 규율 — 세션 주입용, 직접 호출할 일 없음 |
| `brainstorming` | 기능·컴포넌트를 **만들기 전** 의도·요구사항·설계 탐색. plan mode 진입 전 필수로 지정됨 |
| `writing-plans` | 스펙이 잡힌 다단계 작업의 구현 계획서 작성 (코드 만지기 전) |
| `executing-plans` | 작성된 계획을 체크포인트 리뷰와 함께 별도 세션에서 실행 |
| `subagent-driven-development` | 계획의 독립 태스크들을 서브에이전트로 나눠 현재 세션에서 실행 |
| `dispatching-parallel-agents` | 독립 작업 여러 개를 병렬 에이전트로 동시 실행할 때의 규율 |
| `test-driven-development` | 기능·버그픽스 구현 전 RED→GREEN→REFACTOR. "테스트 전에 쓴 코드는 삭제" 수준의 rigid 규율 |
| `systematic-debugging` | 버그·테스트 실패 시 수정 제안 **전에** 근본 원인 규명 절차 |
| `verification-before-completion` | "완료" 선언 전 실제 검증 실행·증거 확인 |
| `requesting-code-review` / `receiving-code-review` | 리뷰 요청 시 컨텍스트 정리 / 리뷰 피드백 수용 절차 |
| `using-git-worktrees` | 격리가 필요한 작업 시작 시 worktree 생성·디렉토리 선택·안전 검증 |
| `finishing-a-development-branch` | 구현 완료·테스트 통과 후 merge/PR/정리 선택지 정리 |
| `writing-skills` | **스킬을 만드는 스킬** — 새 스킬 작성·수정·배포 전 검증 규칙 |

> v5→v6에서 도메인성 스킬 20종(api-design·security-audit·performance-optimization 등)과
> `code-reviewer` 에이전트, `/superpowers:brainstorm` 계열 커맨드가 제거됐다 — v6은 프로세스
> 코어만 남긴 구성이다.

## 4. 자주 쓰는 흐름 (일반 프로젝트 기준)

1. **새 기능**: `brainstorming`(의도 탐색) → `writing-plans`(계획서) → `executing-plans` 또는
   `subagent-driven-development`(실행) → `verification-before-completion`(완료 검증)
2. **버그**: `systematic-debugging`(재현·원인 규명) → `test-driven-development`(실패 테스트로
   고정 후 수정) → `verification-before-completion`
3. **격리 작업**: `using-git-worktrees` 로 시작 → 완료 시 `finishing-a-development-branch`
4. **커스텀 스킬 제작**: `writing-skills` — 자체 하네스 스킬(`.claude/skills/`)을 만들 때도
   작성 기준(트리거 description·rigid/flexible 구분·Red Flags 패턴) 참고 가치가 있다.

## 5. 이 저장소에서의 사용 지침 (요약)

- **절차 규율 3종은 자체 스킬이 정본**: `tdd-discipline`·`debugging-discipline`·`verify-before-done`
  (superpowers의 test-driven-development·systematic-debugging·verification-before-completion 대신).
  요구사항 탐색도 `socrates`/`interview-harness` 가 우선.
- superpowers 를 쓸 자리는 **내재화 안 된 범용 절차**: writing-plans/executing-plans,
  subagent-driven-development, code-review 요청·수신, writing-skills.
- 실측(2026-07-22 텔레메트리)상 이 저장소에서 superpowers 스킬 로드는 0회 — 기여 검증
  A/B 실험이 진행 중이므로 토글 규칙을 지키고, 판정 전까지는 위 우선순위대로만 사용한다.
