# Seed — operation-service 운영 관제 as-is 사양

> 상태: CONFIRMED (settlement/account seed 와 동일 방식 — 역산 결정화)
> 관련 스킬: `operation-signal-rules`(강제 규칙 정본)

## Goal (한 줄)

**operation-service(운영 관제 — 인시던트 라이프사이클·5분 신호버킷·이상탐지)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 관제 계약 근거 ·
면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 인시던트 상태머신·활성 유일성·refire 병합 규칙
- 5분 신호 버킷의 정렬·누적·파생 계산 경계
- Alertmanager webhook 수신 계약
- 이상탐지(베이스라인·임계) 판정 경계

**제외**

- Prometheus/Alertmanager 설정 자체(`../../../monitoring` 소관)
- 폴리글랏 이상탐지 서비스(settlement-anomaly, Python)와의 역할 분담 상세

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `../../../operation-service/src/main/java/github/lms/lemuel/operation`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **상태 전이는 도메인이 강제** — `canTransitionTo` 통과 없이는 전이 불가, 위반은 타입 예외 | `incident/domain/Incident.java:13,186-187,194` (`InvalidIncidentTransitionException`) |
| 2 | **활성 인시던트는 (source, correlationKey) 당 최대 1건** — DB partial unique index 가 최종 방어선이고, 도메인은 그 상황을 refire(병합)로 표현한다 | `Incident.java:15-16` (`uq_incident_active`) |
| 3 | **refire 는 심각도를 상향만 반영** — WARNING→CRITICAL 승격은 반영, 하향은 무시 | `Incident.java:17` + `incident/domain/IncidentSeverity.java:6` |
| 4 | **refire 는 발생 횟수·최종 관측 시각을 갱신** | `Incident.java:17,122-126` |
| 5 | **타임라인 기록은 억제 간격을 둔다 — 단 승격은 예외** — 일반 refire 는 직전 기록 후 억제 간격 경과 시에만 남기고, 심각도 승격은 억제 없이 즉시 기록 | `Incident.java:136` |
| 6 | **신호 버킷은 고정 폭 정렬** — UTC epoch 초를 버킷 폭(기본 300초)으로 내림 정렬해 같은 창의 신호가 한 행에 모인다 | `signal/domain/BucketWindow.java:6-9,19` |
| 7 | **적재는 UPSERT 누적, 계산은 읽기 시점** — 버킷 행에 카운트를 누적하고 `failureRate = countSignal / countTotal` 은 조회 때 계산한다 | `signal/domain/MetricBucket.java:6,11,15,28` |
| 8 | **webhook 은 항상 200** — 5xx 를 돌려주면 Alertmanager 가 재전송을 반복하므로 수신 실패도 200 으로 응답한다. 보안 체인에서 `permitAll` | `incident/adapter/in/web` webhook 컨트롤러:21 |
| 9 | **이상탐지는 베이스라인 대비 판정** — 롤링 윈도 베이스라인과 임계로 verdict 를 낸다 | `anomaly/domain/{RollingWindowBaseline,AnomalyThreshold,AnomalyEvaluator}.java` |
| 10 | **자동 해소 경로가 있다** — 조건 충족 시 시스템 액터(`AUTO_ACTOR`)로 해소 처리 | `Incident.java:165` |

## 이벤트 계약

**소비 8토픽** — 도메인 성공 신호 3종(`order.created`·`payment.captured`·`settlement.created`)과
운영 실패 신호 5종(`ops.order.failed`·`ops.payment.failed`·`ops.stock.depleted`·`ops.stock.reclaim_delayed`·
`ops.shipping.delayed`·`ops.settlement.failed`)을 받아 버킷에 누적한다.
**발행 0** — 이 서비스는 관제 종단이다.

> 컨슈머는 적재 실패를 삼키고 ack 한다(통계 손실을 컨슈머 정지보다 우선) — `operation-signal-rules` 의
> "절대 throw 금지·fire-and-forget" 규칙과 일치한다.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 인시던트 상태머신 전이표·타입 예외가 일치한다 | `./gradlew :operation-service:test` — `IncidentTest` |
| AC-2 | 활성 인시던트 중복 생성이 차단된다 | `uq_incident_active` · `IncidentLifecycleIntegrationTest` |
| AC-3 | refire 가 상향만 반영하고 타임라인 억제가 동작한다 | `IncidentTest` · `IncidentCommandServiceTest` |
| AC-4 | 같은 5분 창의 신호가 한 행에 누적된다 | `BucketWindowTest` · `MetricBucketUpsertIntegrationTest` |
| AC-5 | `failureRate` 파생 계산이 읽기 시점에 정확하다 | `MetricBucketTest` |
| AC-6 | webhook 이 어떤 입력에도 200 을 돌려준다 | `IngestAlertServiceTest` · `AlertApplierTest` |
| AC-7 | 이상탐지 판정 경계가 표와 일치한다 | `AnomalyEvaluatorTest` · `AnomalyDetectionServiceTest` |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :operation-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** **관제의 관제가 없다** — 이 서비스가 죽으면 인시던트 수집이 침묵한다. `../../../monitoring/prometheus.yml` 이
  `up{job="operation"}` 을 스크레이프해 `OperationServiceDown` 알람의 소스로 삼는 구조지만, 그 알람 자체는
  Prometheus 가 살아 있어야 동작한다. → `disposition: by-design` (외부 관제 의존)
- **KI-2** 컨슈머가 적재 실패를 **삼키고 ack** 한다(신호 버킷은 통계라는 판단). 즉 Kafka 재시도·DLT 방어가
  이 경로에는 적용되지 않으며, 브로커에는 성공으로 보인다. 통계 유실이 조용히 일어날 수 있다.
  → `disposition: by-design-documented` (`operation-signal-rules` 가 명시)
- **KI-3** webhook 이 **항상 200 + permitAll** 이다. Alertmanager 재전송 폭주를 막으려는 선택이지만,
  인증 없는 엔드포인트가 인시던트를 생성할 수 있다는 뜻이다. 네트워크 경계(gateway 미라우팅)에 의존한다.
  → `disposition: by-design` (경계 의존 — 노출 시 위험)
- **KI-4** 이상탐지가 **Java(operation)와 Python(settlement-anomaly) 양쪽에 존재**한다. 어느 쪽이 정본이고
  둘의 판정이 어긋나면 무엇을 믿는지가 문서에 없다. → `disposition: recorded-not-verified` (역할 분담 불명)
