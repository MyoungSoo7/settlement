# Seed — reconciliation-service 대사 배치 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`reconciliation-service-batch.seed.yaml`](reconciliation-service-batch.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**reconciliation-service(Kotlin 폴리글랏 8131 — 양측 `/internal/recon` 코루틴 동시 fetch·businessKey diff 4분류·
프로파일로 샘플/실소스 격리·일 1회 19시 배치)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                        | 제외                                      |
| ------------------------------------------- | ----------------------------------------- |
| 대사 엔진 (4분류·허용오차·금액우선)         | settlement/order 의 `/internal/recon` 구현 |
| 소스 어댑터 (실 HTTP 2·샘플 2·프로파일 격리) | 불일치의 회계 처리·조정 전표              |
| 오케스트레이션 (role 병합·동시 fetch)       |                                           |
| 스케줄 배치 (cron·fail-open·메트릭) · REST  |                                           |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **4분류 + 금액 우선** — MISSING / EXTRA / AMOUNT_MISMATCH / STATUS_MISMATCH. 금액·상태가 동시에 어긋나면 AMOUNT_MISMATCH 1건만 (`ReconciliationEngine.kt:28-74`).
2. **sealed 전수 처리** — `Discrepancy` sealed 4구현이라 DTO 매핑의 `when` 을 컴파일러가 검사.
3. **cross-DB 금지** — 양측이 자기 DB 만 읽고 숫자만 HTTP 로 (`HttpSources.kt:21-22`).
4. **동시 fetch** — `coroutineScope`+`async`, 블로킹 RestClient 는 `Dispatchers.IO` 로 넘겨 병렬성 보존.
5. **커서 페이지네이션으로 소진까지** — 단일 요청으로 자르면 초과분이 전부 EXTRA 로 잡혀 대사가 거짓 불일치를 만든다 (`:52-58`, PAGE_SIZE 1000 < 서버 상한 2000).
6. **샘플·실소스 상호배타** — `@Profile("demo")` vs `@Profile("!demo")`. 근거는 2026-07-30 프로덕션 사고(매일 가짜 4건).
7. **반쪽 소스는 실행 안 함** — role 사전 검사 후 `recon.runs{result=misconfigured}` (`Scheduler:66-74`).
8. **fail-open** — 배치 예외는 전파하지 않는다. 다음 날 실행이 재시도다.
9. **일치는 보고 안 함** — 리포트에는 문제만, matched 는 카운트로. `discrepancies` 는 방어적 복사.

## 이벤트 계약

**없음 — HTTP pull 배치.** Kafka 의존이 빌드에 아예 없다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                     | 게이트                          |
| ---- | ---------------------------------------- | ------------------------------- |
| AC-1 | 4분류·허용오차 경계·금액 우선 일치       | `ReconciliationEngineTest`      |
| AC-2 | role 병합·동시 fetch·role 부재 거부 일치 | `ReconciliationServiceTest`     |
| AC-3 | 페이지네이션 종료·금액/상태 정규화 일치  | `HttpSourcesTest`               |
| AC-4 | REST 계약·400 매핑 일치                  | `ReconciliationControllerTest`  |
| AC-5 | 전체 빌드 GREEN                          | `JAVA_HOME=<JDK21> ./gradlew build` · `polyglot-ci.yml` |

## Known Issues (발견만 기록)

- **KI-1 ★high**: **어떤 배포 환경에서도 돌지 않는다** — compose 정의도 게이트웨이 라우트도 없다. 19:00 배치가 실행되는 환경이 존재하지 않는다.
- **KI-2 ★DRIFT**: `polyglot-services.md` 가 아직 "skeleton·실소스 빈 미등록"으로 기술 — 2026-07-30 변경으로 사실 아님.
- **KI-3**: `app.reconciliation.tolerance-krw` 를 읽는 코드가 없다(사문화된 설정).
- **KI-4**: `settlement-base-url` 기본 포트 8082 — compose 내부는 8080. 올리는 순간 연결 실패.
- **KI-5**: `/reconciliation/demo` 가 비-demo 프로파일에서 실 소스로 폴백 — 무인증 GET 하나가 내부 API 전량 조회.
- **KI-6**: REST 2경로 무인증 — 방어가 "라우트 미등록" 하나뿐.
- **KI-7**: 대사 결과 미보존 — `recon.last.*` 게이지는 마지막 값만.
- **KI-8**: 커버리지 게이트 없음 + 스케줄러·프로파일 분기 테스트 부재(KI-5·BR-5 가 회귀 보호 밖).
