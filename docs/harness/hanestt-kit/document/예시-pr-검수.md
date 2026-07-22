# 예시 — PR 검수를 md형으로 고정

`skill.template.md`를 채운 예시 2. PR 검수 규칙을 한 장에 고정한 사례. 인터뷰 예시와 *다른 도메인*에서 같은 템플릿이 어떻게 채워지는지 비교용.

---

```markdown
---
name: pr-review
description: |
  GitHub PR 번호 한 개를 입력으로 diff를 읽고 검수 결과 한 묶음(verdict · 사유 · 코멘트 후보)을 만든다.
  Triggers (KO): PR 리뷰, diff 검수, 머지해도 돼, 이 PR 어때
  Triggers (EN): review this PR, audit diff, check merge readiness
  Do NOT use when: commit 단위 메시지 생성 → /commit-message. 브랜치 전략 검토 → /branch-plan.
---

# PR Review

## 역할

이 스킬은 PR diff를 읽고 (1) 머지 가능 여부 한 줄, (2) 사유 1~5개, (3) 인라인 코멘트 후보 0~10개를 만든다. 코드를 직접 수정하지 않는다.

## 입력

PR 번호 1개. diff는 `gh pr view <num> --json files,additions,deletions`와 `gh pr diff <num>`으로 가져온다.

## 출력

```yaml
verdict: pass | fail | hold
reasons:
  - <한 줄 사유>
  - <한 줄 사유>
comments:
  - file: <path>
    line: <int>
    severity: blocker | suggestion | nit
    body: <한 줄>
```

불변 조건:
- `verdict`는 세 값 중 하나만.
- `reasons`는 1개 이상 5개 이하.
- `comments`는 0개부터 10개까지. 코멘트 한 건은 한 파일·한 라인만 가리킨다.
- `severity`가 `blocker`인 코멘트가 한 건이라도 있으면 `verdict`는 `fail` 또는 `hold`여야 한다.

## 원칙

1. 검수 사유는 diff 안에 *증거가 있는 것*만 적는다.
2. 코멘트의 `line`은 diff에 실제로 존재하는 라인이어야 한다.
3. `blocker`는 보안·데이터 손실·돌이킬 수 없는 변경에만 쓴다.
4. `nit`은 한 PR당 3개 이하로 묶는다.

## 금지사항

- diff에 없는 라인 번호를 만들지 않는다.
- 검수 대상 파일을 직접 수정하지 않는다.
- 본인이 모르는 도메인 규칙을 가정해 사유를 만들지 않는다 (확신이 없으면 `verdict: hold`).
- 칭찬·인사·이모지를 코멘트 본문에 넣지 않는다.

## 예시

### 입력

```
PR #1247
```

### 출력

```yaml
verdict: hold
reasons:
  - migration이 unique 제약을 추가하지만 기존 데이터 중복 점검 스크립트가 PR에 없다
  - 새 endpoint의 인증 미들웨어가 누락되어 있다
comments:
  - file: db/migrations/0042_user_unique.sql
    line: 12
    severity: blocker
    body: UNIQUE 추가 전에 기존 중복 확인 절차가 필요하다 (별도 스크립트 또는 사전 점검).
  - file: api/routes/user.py
    line: 47
    severity: blocker
    body: 다른 user endpoint와 달리 @require_auth가 없다.
  - file: api/routes/user.py
    line: 91
    severity: suggestion
    body: 에러 메시지를 i18n 키로 옮기는 게 좋다 (다른 endpoint와 일관성).
```

## 인접 스킬과의 관계

- vs `/commit-message`: commit 단위 메시지 생성. 이 스킬은 PR 단위 검수.
- vs `/security-review`: 보안 전용 깊은 검수. 이 스킬은 일반 검수에서 발견한 보안 사안만 `blocker`로 표시하고, 깊은 분석은 `/security-review`로 위임한다.

## 자가 검증

같은 PR을 두 번 검수해 출력 YAML의 키 구조와 `verdict` 값이 같은지 본다. 두 번째 실행에서 새 사유가 나오면 diff 본문은 같은데 *해석*이 달라진 것 — 원칙·금지 칸의 모호한 곳을 좁힌다.
```

---

## 두 예시를 나란히 놓고 보는 것

- 도메인은 완전히 다르지만 (요구사항 인터뷰 ↔ PR 검수), 7개 칸 구조는 동일하다.
- 두 예시 모두 **출력 스키마가 코드 블록으로 정확히 적혀 있다.** 이게 md형의 핵심.
- 두 예시 모두 **금지사항이 비어 있지 않다.** 흔히 실패하는 패턴을 미리 막는다.
- 두 예시 모두 **예시 입출력 한 쌍만** 있다.

이 두 예시를 합쳐 본인 도메인의 md형 한 장을 만든다.
