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

### 2026-08-15 · Bash 명령 가드(COMMAND_RULES, --hook-bash) — 실시간 계층의 두 구멍 봉쇄

- **status**: candidate
- **동기**: ① 실시간 가드 매처가 `Write|Edit|MultiEdit` 뿐이라 sed -i·heredoc 리다이렉트로 소스를
  쓰면 내용 스캔을 통째로 우회했다(백슬래시 손실 사고 2회 전력). ② "운영 DB 명령 차단(check-command)"
  은 settlement-copilot **플러그인 소유**라 플러그인 미설치 환경(CI·새 클론·Codex)엔 아예 없었다 —
  HARNESS 의 "플러그인 독립" 전제와 모순.
- **predicted_effect**: telemetry `mode hook-bash` 실행 분모가 세션마다 기록되고, CMD-EDIT-BYPASS /
  CMD-NO-VERIFY 발화가 0회면 "완전 예방"(카나리아 PASS 로 생존 확인), 발화하면 실시간에서 잡힌
  우회 시도다. 소스 파일의 heredoc 손상 재발이 0 이 된다.
- **위험**: 오탐이 Bash 전체를 마찰시킨다 — 대상을 소스 확장자(.java/.kt/.sql/.mjs/.yml)로 좁히고
  fail-open + `HARNESS_ALLOW_CMD=1` 탈출구를 뒀다. 오탐 발견 시 규칙을 좁힌다(끄지 않는다).
- **verified_at**: 미검증 (카나리아 4종·유닛 12케이스는 도입 시점 PASS — 실전 발화는 2주 뒤 리포트로)

### 2026-08-15 · skill-router 세션 상태 GC (14일 보존)

- **status**: candidate
- **동기**: `.claude/harness/state/` 에 세션당 1개 상태 파일이 정리 정책 없이 누적(실측 ~70개).
  실해는 작지만 "상태 관리에 GC 가 없다"는 구조 결함.
- **predicted_effect**: 상태 파일 수가 14일 활동 세션 수로 수렴한다(무한 증가 중단). dedupe 동작은
  불변(신선한 세션 상태는 건드리지 않음 — 테스트 고정).
- **verified_at**: 미검증 (2주 뒤 `ls .claude/harness/state | wc -l` 로 대조)

### 2026-08-15 · insurance/deposit 도메인 규칙 스킬 + 라우터 배선 (커버리지 공백 해소)

- **status**: candidate
- **동기**: HARNESS.md 가 스스로 "우선 부채"로 명시한 돈 경로 2서비스(보험 수수료정산·25%룰·완전판매
  게이트 / 예치금 hold·offset 이중사용 차단)가 전용 `*-rules` 스킬·라우터 행 없이 방치.
- **predicted_effect**: insurance/deposit 경로 편집 시 라우터가 해당 스킬을 주입하고(순응률 지표에
  등장), 두 서비스의 도메인 규칙 위반(만료 회수 originalAmount·referenceType 변경·게이트 후퇴 등)이
  리뷰에서 스킬 근거로 지적된다. 커버리지 공백 섹션은 organization 1개로 축소.
- **verified_at**: 미검증 (skill-router.test.mjs 라우팅 3케이스는 도입 시점 PASS)

### 2026-08-12 · 가드 실행 분모(guard-runs.jsonl) 추가

- **status**: candidate
- **동기**: 9개 체크아웃 어디에도 `../../.claude/harness/logs` 가 없었다. 이게 "아무도 규칙을 어기지
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
- **동기**: PR #210 에 섞여 `.claude`/`.codex`/`../harness` 270 파일이 조용히 지워진 사고.
- **predicted_effect**: 하네스 보호 경로 삭제가 PR 단계에서 차단된다.
- **verified_at**: 2026-08-12 — `--deleted-list` 모드가 CI 에 배선돼 있고, 2026-08-12 부터
  `../../scripts/verify.sh` 도 같은 검사를 로컬에서 돈다.

### 2026-08-15 · 문서 사실 게이트에 "서비스 수" 규칙 추가 + 부사 삽입형 소비처 주장 포착

- **status**: verified
- **동기**: HARNESS.md 가 3주간 `14 마이크로서비스` 로 남아 있었다(실제 16). 같은 문서 안에
  `자바 16서비스` 줄이 공존해 **자기모순**이었는데도 `harness-audit` 는 healthy 였다 — 모듈 로스터
  대조가 트리 표기만 보고 산문 주장은 안 봤기 때문. 같은 점검에서 `소비처가 아직 미배선`(organization)
  이 gate #3 을 통과한 것도 드러났다. card-service 가 4토픽을 실제 소비 중인데, 정규식이
  `소비처(가) 미배선` 만 보고 사이에 낀 `아직` 을 못 넘었다.
- **predicted_effect**: 상태 기술 문서에서 서비스 수를 안 고치면 audit FAIL → CI 차단.
  로스터 앵커(gateway·DB-per-service)가 같은 줄에 있을 때만 주장으로 인정하므로
  부분집합("금융 5서비스")·폴리글랏 합계(24)는 오탐하지 않는다.
- **verified_at**: 2026-08-15 — 규칙 투입 직후 실제 저장소에서 `HARNESS.md:67` 1건을 잡았고(수정 후
  healthy 복귀), 상태 기술 문서 8종 전수에서 오탐 0건. `audit.test.mjs` 가 "잡는다/오탐 안 한다"
  5쌍으로 고정.

---

## 측정된 것 (2026-08-15 갱신 — 이전 "못 재는 것" 3항목이 전부 데이터를 갖게 됨)

재현: `node scripts/harness/telemetry-report.mjs` · `node scripts/harness/session-metrics.mjs`

- **위반 시도 빈도** — 가드 실행 1563회(분모: hook 1341 · staged 205 · files 9 · list 8) 대비
  **차단 11건(0.7%)**, 최근 14일 6건. 최다 `MONEY-PRIMITIVE` 5 · `MSA-BOUNDARY` 4.
  0회 규칙 7종은 카나리아가 전부 PASS 하므로 "죽은 규칙"이 아니라 **완전 예방**으로 판정된다.
- **스킬 로드** — `skill-usage.jsonl` **295회**. 상위 `tdd-discipline` 55 · `settlement-domain-rules` 28 ·
  `verify-before-done` 25 · `idempotency-and-events` 22 · `ledger-invariants` 21.
  라우터 순응률(제안→로드) **100% (197/197)** — 목표 ≥80% 대비 초과 달성.
- **상주/온디맨드 비중** — 상주 CLAUDE.md 20.1KB vs 온디맨드 37스킬 183.7KB → **상주 비중 10%**.
- **완주율·재작업률(KPI-3/4)** — KPI-3 완주율 100%(2/2)이나 **n<10 이라 추이 지표로만** 쓴다.
  KPI-4 재작업률 최근 30일 **21.6%(183/849)** — 베이스라인 19.3%(2026-07-22) 대비 **상승**했다.
  하향이 목표였으므로 이 항목은 아직 개선 실패로 읽는 것이 정직하다.

## 아직 못 재는 것 (정직한 공백)

- **스킬 37개 중 값을 하는 것** — 로드 빈도는 이제 알지만 **로드가 결과를 바꿨는지**는 모른다.
  로드 0회 13종(`compliance-review` · `economics-data-rules` · `hookify-to-guard` · `incident-runbooks` ·
  `market-quotes-rules` · `oo-score` · `operation-signal-rules` · `recon-playbook` · 인터뷰 서브하네스
  `socrates`·`wonder`·`reflect`·`refine`·`restate`)도 "안 쓰임"과 "해당 상황이 안 옴"이 구분되지 않는다
  — 가드의 카나리아에 해당하는 장치가 스킬 쪽엔 없다.
- **KPI-4 상승의 원인** — 재작업률이 올랐다는 사실은 재지만, 하네스 탓인지 작업 성격(대규모 캠페인
  다수) 탓인지 분해할 축이 없다.
