---
name: project_adr_conventions
description: docs/adr/ ADR format template, current numbering state, and index maintenance rules for this repo
metadata:
  type: project
---

This repo's ADRs are NOT the "ADR-XXXX" / "**Context**:" style from generic ADR guidance —
they use a repo-specific Korean template (Michael Nygard-based). Always read 2-3 recent
`docs/adr/*.md` files before writing a new one to re-confirm the current template, since it
may evolve.

**Numbering (as of 2026-07-22, last confirmed ADR 0028 added)**: sequential files exist
0001-0018, 0020-0028. **0019 is intentionally skipped** (documented in `docs/adr/README.md`
as 결번 — never reassign it). Before creating a new ADR, `ls docs/adr/*.md` to find the true
max number — do not trust memory for the exact latest number, it changes every session.

**File naming**: `{NNNN}-kebab-case-english-slug.md` (4-digit zero-padded), even though the
body is in Korean.

**Body template** (observed consistently across 0001, 0021, 0024, 0027):
```
# ADR {NNNN} — {한글 제목}

- 상태: Accepted|Proposed|Deprecated|Superseded by {NNNN} (구현 완료 — optional annotation)
- 일자: YYYY-MM-DD

## 컨텍스트
(문제 상황 — 왜 이 결정이 필요했는가. 실증 사례·수치가 있으면 포함)

## 결정
(무엇을 결정했는가, 보통 ### 1. ### 2. ... 번호 하위섹션으로 구조화, 코드/디렉토리 트리 포함 가능)

## 결과
### 좋아지는 점
### 트레이드오프 / 리스크

## 대안 검토
(표 형식: | 옵션 | 채택? (✓/✗/△) | 이유 |  — 모든 ADR 에 있는 건 아님, 있으면 이 위치)

## 참조
(관련 ADR·문서 링크, 상대경로)
```

Note: `## 대안 검토` section is present in ~most but not all ADRs (e.g. 0024 omits it and folds
alternatives into an inline `>` blockquote instead). Prefer including it as a table when the
source material names concrete alternatives considered — matches this agent's own ADR
responsibilities more precisely than the inline-blockquote style.

**Index file**: `docs/adr/README.md` has a single markdown table (`| # | 제목 | 상태 |`) that
must be updated in the same PR/commit as any new ADR — append one row, keep sequential order.
Rules block at the bottom of README.md (already stable, don't duplicate into new ADRs):
1. Numbers increase, never reused.
2. Superseded decisions get `Superseded by 00XX` status change + new ADR; old ADR is never deleted.
3. Major decisions ideally go Proposed → reviewed → Accepted; retrofitting past decisions as
   Accepted-on-arrival is acceptable.

**Other docs explicitly off-limits for doc-maintainer per repo owner's instruction**: don't
touch `STATUS.md` when the task is scoped to ADR-only work — confirm scope before touching
adjacent docs.
