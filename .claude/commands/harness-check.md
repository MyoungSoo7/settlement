---
description: 하네스 자기 진단 — 문서 드리프트·라우팅 dangling·가드 무결성을 기계로 검증하고 필요 시 고친다
argument-hint: "[--fix 로 드리프트 수치·가드 설치까지 자동 적용, 생략 시 진단만]"
---

이 저장소의 **하네스 자체 건강**을 점검하라. 대상 옵션: $ARGUMENTS (`--fix` 없으면 읽기 전용 진단).

1. `node scripts/harness/harness-audit.mjs` 를 실행하고 4개 섹션 결과를 그대로 보고하라:
   STATUS 수치 드리프트 · 라우팅 맵 dangling · settings.json 가드 훅 경로 실존 · 인벤토리.
2. `node scripts/harness/guard.mjs --staged` 로 스테이지된 변경의 돈/경계/이력 불변식 위반을 확인하라
   (스테이지가 비어 있으면 "clean" 으로 간주).
3. `git config --get core.hooksPath` 가 `scripts/harness/hooks` 인지 확인하라. 아니면 가드가 커밋에서
   미작동이므로 **경고**하고 `node scripts/harness/install-hooks.mjs` 를 안내하라.

**보고 형식**: 결론 한 줄(건강/N건 문제) → 실패 항목별 (무엇이/live vs claimed/근거) → 권고.

`--fix` 인자가 있을 때만:
- STATUS 수치 드리프트가 있으면 `harness-audit` 가 계산한 live 값으로 `STATUS.md#핵심 수치` 를 갱신하라
  (휘발성 수치는 반드시 재현 git 명령을 병기 — 값만 손으로 적지 마라).
- `core.hooksPath` 미설정이면 `node scripts/harness/install-hooks.mjs` 를 실행하라.
- 라우팅 맵 dangling 은 코드/문서 원인을 조사해 보고만 하라(자동 삭제 금지 — 사람 판단 필요).

어떤 경우에도 가드를 우회(`--no-verify`)하거나 드리프트 게이트를 느슨하게 만드는 방향으로 "고치지" 마라.
자세한 원칙은 `HARNESS.md` 의 "드리프트 방지 규약" 과 "검증 게이트" 를 따르라.
