# 하네스 개선 로그

하네스(가드·스킬·훅·CI 게이트)를 **고칠 때마다 한 줄 남기는 곳**. 코드 변경 로그가 아니라
_판정 로그_ 다. 지금까지 하네스는 계속 늘어나기만 했고, 어느 개선이 실제로 효과가 있었는지
되짚을 근거가 없었다. 규칙은 한 번 들어오면 아무도 지우지 않는다 — 효과를 잰 적이 없으니
지울 근거도 없기 때문이다.

## 규칙

각 항목은 **반증 가능한 계약**으로 쓴다. 예측을 먼저 적고, 나중에 그 예측을 실제 관측과 대조한다.
예측이 틀렸으면 되돌린다. 되돌린 것도 지우지 말고 `reverted` 로 남긴다 — 실패한 시도의 기록이
같은 시도를 두 번 하지 않게 막는 유일한 장치다.

| 필드               | 뜻                                                                                |
| ------------------ | --------------------------------------------------------------------------------- |
| `status`           | `candidate`(효과 미검증) · `verified`(예측대로) · `reverted`(예측 빗나감, 되돌림) |
| `predicted_effect` | 무엇이 **관측 가능하게** 달라질 것인가. "품질이 좋아진다" 는 예측이 아니다.       |
| `verified_at`      | 예측을 실제 데이터와 대조한 날짜 + 근거. 안 했으면 `미검증` 이라고 정직히 쓴다.   |

검증 데이터 출처는 `node scripts/harness/telemetry-report.mjs` (가드 실행 분모·차단 통계·
스킬 사용률·라우터 순응률)와 `node scripts/harness/session-metrics.mjs` (완주율·재작업률).

⚠️ 분모 주의: `guard-hits.jsonl` 은 **위반만** 기록한다. 실행 이력(`guard-runs.jsonl`)이 없던
시절의 "차단 0회" 는 무위반이 아니라 미측정이다. 2026-08-12 이전 데이터로 효과를 주장하지 말 것.

---

## 항목

### 2026-08-12 · 가드 실행 분모(guard-runs.jsonl) 추가

- **status**: candidate
- **동기**: 9개 체크아웃 어디에도 `.claude/harness/logs/` 가 없었다. 이게 "아무도 규칙을 어기지
  않았다" 인지 "훅이 안 돌았다" 인지 구분할 방법이 없었다. 위반만 적는 로그의 구조적 한계.
- **predicted_effect**: 다음 리포트부터 `mode hook: N회 실행` 이 0 이 아니게 찍힌다. 0 이면
  PreToolUse 훅 배선이 죽어 있다는 뜻이므로 그때는 훅부터 고친다.
- **verified_at**: 부분 — 2026-08-12 `verify.sh` 1회 실행만으로 `mode hook: 3회 · mode list: 2회`
  가 기록되어 배선 자체는 확인됐다. 다만 예측의 본체(실제 에이전트 세션에서 0 이 아님)는
  병합 후 2주 뒤 `telemetry-report.mjs` 로 다시 본다.

### 2026-08-12 · scripts/verify.sh — 로컬에서 CI 판정 재현

- **status**: candidate
- **동기**: CI 가 하는 판정을 로컬에서 같은 순서로 돌릴 방법이 없었다. 그 결과 하네스 테스트가
  개발자 맥에서 3건 깨진 채 방치돼 있었다(모두 macOS `/var`→`/private/var` 심링크 문제로,
  리눅스 CI 에서는 통과해 보이지 않았다). 아무도 로컬에서 안 돌렸다는 증거.
- **predicted_effect**: "다 됐다" 자기보고 대신 종료 코드로 증명. CI 에서 처음 빨간불이 뜨는
  일이 줄어든다.
- **위험**: 느려지면 우회당한다. 기본 경로는 변경 모듈만 빌드하고, 수초짜리 하네스 게이트를
  수분짜리 Gradle 앞에 둔다.
- **verified_at**: 부분 — 2026-08-12 작성 당일 이미 3건을 잡았다(guard.test.mjs 2건 ·
  install.test.mjs 1건, 전부 macOS 심링크). 수정 후 154 테스트 통과·감사 healthy·가드 clean 으로
  exit 0. "CI 첫 빨간불이 줄어든다" 는 추세 예측이라 여전히 미검증.

### 2026-08-12 · CI 텔레메트리 아티팩트 업로드

- **status**: candidate
- **동기**: 러너는 매 실행 폐기되고 로그 디렉토리는 gitignore 대상이라, CI 쪽 가드 실행 이력이
  전량 소실되고 있었다.
- **predicted_effect**: PR 마다 `harness-telemetry-*` 아티팩트가 남아, 규칙별 발화 빈도를
  30일 창으로 되짚을 수 있다.
- **verified_at**: 미검증

### 2026-07-22 · 가드 카나리아 + 라우터 순응률 + 세션 메트릭 (29be679d)

- **status**: verified (부분)
- **predicted_effect**: 규칙별 차단 0회가 "죽은 규칙" 인지 "완전 예방" 인지 판별 가능해진다.
- **verified_at**: 2026-08-12 — 9개 규칙 카나리아 전부 PASS. `inflearn/test/telemetry-report.test.mjs`
  의 `every rule has a canary fixture and every canary passes` 가 CI 게이트로 강제되므로
  규칙 사망은 기계적으로 차단된다. **단** 순응률·세션 메트릭 쪽은 입력 데이터가 0건이라
  여전히 미검증이다.

### 2026-07-24 / 2026-07-25 · MSA-BOUNDARY 를 inverse-allowlist 로 전환 (aaf9f962, b92b3a84)

- **status**: verified
- **predicted_effect**: denylist 나열에서 빠진 신규 order 도메인 import 가 더 이상 통과하지
  못한다. `import static` 우회도 막힌다.
- **verified_at**: 2026-08-12 — `guard.test.mjs` 가 payment·review·game·category·menu·rbac·
  order·user 차단과 settlement 자기 컨텍스트 12개 허용을 양방향으로 고정. false positive 회귀도
  같은 테스트가 잡는다.

### 2026-08-07 · 하네스 경로 삭제 가드 (7672b48e, 0c16b896)

- **status**: verified
- **동기**: PR #210 에 섞여 `.claude`/`.codex`/`docs/harness` 270 파일이 조용히 지워진 사고.
- **predicted_effect**: 하네스 보호 경로 삭제가 PR 단계에서 차단된다.
- **verified_at**: 2026-08-12 — `--deleted-list` 모드가 CI 에 배선돼 있고, 2026-08-12 부터
  `scripts/verify.sh` 도 같은 검사를 로컬에서 돈다.

---

## 아직 못 재는 것 (정직한 공백)

- **에이전트가 실제로 위반을 시도하는 빈도** — 분모 배선 직후라 데이터가 없다.
- **스킬 37개 중 값을 하는 것** — `skill-usage.jsonl` 0건. 상주 CLAUDE.md 17.6KB 대비
  온디맨드 172.3KB(상주 비중 9%)라는 크기는 알지만, 로드 빈도는 모른다.
- **완주율·재작업률(KPI-3/4)** — `session-metrics.mjs` 는 있으나 입력이 비어 있다.
