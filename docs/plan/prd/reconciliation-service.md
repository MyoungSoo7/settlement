# PRD — 정산 대사 배치 (reconciliation-service, 폴리글랏 Kotlin)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`settlement-core.md`](settlement-core.md)·[`notification-service.md`](notification-service.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `reconciliation-service`(8131, Kotlin 2.0.21 / Boot 3.3.5 / JDK 21, **DB 없음**) — 일 1회 정산 대사 배치 |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                              |
> | 근거      | 프로덕션 Kotlin 15파일, 테스트 4클래스, 소스 어댑터 4종(실 HTTP 2 + 샘플 2), `application.yml` |
> | 범위 밖   | settlement/order 의 `/internal/recon` 구현(각 서비스 소관) · 불일치의 회계 처리·조정 전표 |
> | 관련 문서 | [`../../inflearn/polyglot-services.md`](../../inflearn/polyglot-services.md) · `recon-playbook` 스킬 · [`../seeds/reconciliation-service-batch.seed.yaml`](../seeds/reconciliation-service-batch.seed.yaml) |

---

## 1. 배경과 문제

정산이 잡은 금액과 PG 가 실제로 캡처한 금액이 **하루만 어긋나도 돈이 샌다.** 그런데 두 숫자는 서로 다른
서비스의 서로 다른 DB 에 있고, MSA 경계상 조인할 수 없다.

| 문제                  | 구체적 손상                                                                 |
| --------------------- | --------------------------------------------------------------------------- |
| **cross-DB 조인 불가** | 정산 DB 와 주문 DB 를 붙이면 MSA 경계가 깨진다                              |
| **불일치 무관측**     | 누가 매일 두 숫자를 비교하지 않으면 차이는 월마감에서야 드러난다            |
| **거짓 불일치**       | 페이지네이션이 잘리면 대사 스스로가 없는 불일치를 만들어낸다                |

reconciliation-service 는 **양쪽 서비스의 내부 대사 API 를 코루틴으로 동시에 당겨 순수 엔진으로 diff 하는**
일 1회 배치다. 핵심 설계 판단은 하나다 — **각자 자기 DB 만 읽고 숫자만 HTTP 로 주고받는다.**

## 2. 목표 / 비목표

### 2.1 목표

| #  | 목표                                       | 성공 기준                                                       |
| -- | ------------------------------------------ | --------------------------------------------------------------- |
| G1 | 대사에 cross-DB 연결을 만들지 않는다       | 양측 `/internal/recon` HTTP 호출만                              |
| G2 | 소스 N개를 동시에 당긴다                   | `coroutineScope` + `async` 병렬 fetch                           |
| G3 | 불일치를 4종으로 남김없이 분류한다         | sealed `Discrepancy` — 컴파일러가 `when` 전수 처리 강제         |
| G4 | 대사가 거짓 불일치를 만들지 않는다         | EXPECTED 측 커서 페이지네이션으로 하루치 **소진까지** 읽기      |
| G5 | 허구 데이터로 도는 것을 구조적으로 막는다  | 샘플 소스는 `demo` 프로파일 전용, 실 소스는 `!demo` 전용        |
| G6 | 결과에 알림을 걸 수 있다                   | Micrometer 게이지 4종 + 카운터 2종                              |

### 2.2 비목표 (의도적으로 하지 않는 것)

| #  | 비목표                    | 이유                                                            |
| -- | ------------------------- | --------------------------------------------------------------- |
| N1 | 대사 결과 영속화          | 무영속 MVP — 결과는 로그 + 메트릭으로만 남는다                  |
| N2 | 불일치 자동 조정          | 조정 전표는 settlement 의 책임(역정산 원칙)                     |
| N3 | Kafka 발행/소비           | 이벤트 의존 0 — 순수 HTTP pull 배치                             |
| N4 | 배치 실패 시 재시도       | fail-open — 다음 날 실행이 재시도다                             |
| N5 | `shared-common` 재사용    | 폴리글랏 독립 모듈                                              |

## 3. 사용자

| 사용자          | 무엇을 위해 쓰는가                                       |
| --------------- | -------------------------------------------------------- |
| **정산 운영자** | 전일 정산-결제 불일치 건수·유형을 매일 확인              |
| **온콜**        | `recon.last.discrepancies` 게이지로 알림 수신            |
| **CS/분석**     | `POST /reconciliation/run` 으로 임의 두 집합을 즉석 대사 |

## 4. 제품 범위 — 기능 맵

| 영역     | 기능                                                                |
| -------- | ------------------------------------------------------------------- |
| 수집     | EXPECTED(settlement) / ACTUAL(order 결제) 내부 API 동시 fetch        |
| 대사     | businessKey 기준 diff → MISSING·EXTRA·AMOUNT_MISMATCH·STATUS_MISMATCH |
| 허용오차 | 절대 금액 차 ≤ tolerance(기본 1원)는 일치로 간주                     |
| 스케줄   | 매일 19:00 Asia/Seoul, 전일분 대상                                   |
| 관측     | 게이지 4·카운터 2(`recon.runs` result 태그, `recon.discrepancies` type 태그) |
| REST     | 즉석 대사 실행, 데모                                                 |

## 5. 핵심 유스케이스

### UC-1. 전일 정산이 결제와 맞는지 매일 확인한다

1. 19:00(Asia/Seoul)에 스케줄러가 전일(`LocalDate.now().minusDays(1)`) 기간으로 깨어난다.
2. 등록된 소스의 role 집합을 먼저 검사한다 — EXPECTED·ACTUAL 이 **각각 1개 이상** 없으면 실행하지 않고 `recon.runs{result=misconfigured}` 를 올린다.
3. `settlement-http`(EXPECTED)와 `payment-http`(ACTUAL)를 **동시에** 당긴다.
4. 순수 엔진이 businessKey(=paymentId) 로 diff 한다. 금액을 먼저 보고(돈이 우선 신호), 금액이 허용오차 안이면 상태를 본다.
5. 게이지·카운터를 갱신하고 요약 한 줄을 로그로 남긴다. 불일치가 있으면 WARN.

### UC-2. 대사가 스스로 거짓 불일치를 만들지 않는다

1. EXPECTED 측은 `afterPaymentId` 커서로 1,000건씩 **하루치가 소진될 때까지** 읽는다.
2. 단일 요청으로 잘랐다면 초과분이 조용히 빠지고, ACTUAL 측(order)은 전건을 돌려주므로 그 차이가 전부 EXTRA 로 보고된다 — 대사가 없애야 할 거짓 불일치를 대사가 만드는 셈이다.
3. 페이지 크기(1,000)는 서버 상한(2,000)보다 작게 잡아 한 페이지가 잘리지 않게 한다.

### UC-3. 프로덕션이 샘플 데이터로 돌 수 없다

1. `SampleExpectedSource`/`SampleActualSource` 는 `@Component` 가 **아니다** — `demo` 프로파일의 `DemoSources` 에서만 빈으로 등록된다.
2. 실 HTTP 소스는 `@Profile("!demo")` 로 등록된다. 둘은 절대 함께 뜨지 않는다.
3. 2026-07-30 이전에는 샘플이 `@Component` 라 프로덕션에서도 등록됐고 실 소스는 빈이 아니어서, 배치가 매일 같은 가짜 결과(expected=5 actual=5 discrepancies=4)를 뱉었다. 그 사고가 이 규칙의 근거다.

## 6. 기능 요구사항

| FR   | 요구사항                                                          | 강제 지점                                     |
| ---- | ----------------------------------------------------------------- | --------------------------------------------- |
| FR-1 | 소스는 role(EXPECTED/ACTUAL)을 스스로 선언한다                    | `ReconciliationSource.role`                   |
| FR-2 | 같은 role 의 소스가 여러 개면 합집합으로 병합한다                 | `ReconciliationService.reconcileFromSources`  |
| FR-3 | 한쪽 role 이 없으면 대사를 시작하지 않는다                        | 서비스 예외 + 스케줄러 사전 검사              |
| FR-4 | 금액 차가 허용오차 초과면 AMOUNT_MISMATCH 로 1건만 보고한다       | `classifyShared` — 금액 우선, 상태는 그다음   |
| FR-5 | 음수 허용오차는 거부한다                                          | `ReconciliationInvariantViolationException`   |
| FR-6 | ACTUAL 금액은 `amount - refundedAmount`, 상태는 PAID/REFUNDED 정규화 | `PaymentHttpSource`                        |
| FR-7 | 내부 API 호출에 공유 시크릿 헤더를 싣는다                         | `X-Internal-Api-Key` (키가 비면 헤더 생략)    |
| FR-8 | 배치 실패는 전파하지 않는다                                       | 스케줄러 `catch (Exception)` — fail-open      |
| FR-9 | 리포트의 불일치 목록은 방어적 복사한다                            | `ReconciliationReport.discrepancies`          |

## 7. 도메인 규칙 (BR)

| BR   | 규칙                                                                                                   | 근거                                |
| ---- | ------------------------------------------------------------------------------------------------------ | ----------------------------------- |
| BR-1 | **각자 자기 DB 만 읽는다** — 대사를 위해 cross-DB 연결을 만들지 않는다                                  | `HttpSources` 클래스 주석           |
| BR-2 | **돈이 우선 신호** — 금액·상태가 동시에 어긋나면 AMOUNT_MISMATCH 로 한 번만 보고한다                    | `ReconciliationEngine` 주석         |
| BR-3 | **일치는 보고하지 않는다** — 리포트에는 문제만 담고 matched 는 카운트로만 남긴다                        | `ReconciliationReport`              |
| BR-4 | **소스가 반쪽이면 대사가 아니다** — 결과가 허구가 되므로 실행 자체를 하지 않는다                        | 스케줄러 :66-74                     |
| BR-5 | **샘플과 실 소스는 공존 불가** — 섞이면 대사 전체가 무의미해진다                                        | `SourceConfig` 프로파일 분리        |
| BR-6 | **fail-open** — 흔들리는 PG 엔드포인트가 스케줄러 스레드나 앱을 죽이면 안 된다                          | 스케줄러 :98-102                    |
| BR-7 | **불일치 분류는 전수 처리된다** — sealed 계층이라 새 유형 추가 시 컴파일이 누락을 잡는다                | `Discrepancy` sealed interface      |

## 8. 데이터 모델

**DB 없음.** 대사 결과는 어디에도 저장되지 않는다 — 로그 한 줄 + 메트릭이 전부다.

| 값 객체                | 역할                                                    |
| ---------------------- | ------------------------------------------------------- |
| `ReconRecord`          | `businessKey`·`amountKrw`(Long)·`status` 불변 data class |
| `Discrepancy`(sealed)  | Missing / Extra / AmountMismatch / StatusMismatch        |
| `ReconciliationReport` | 건수 4종 + 유형별 집계 + 요약 한 줄                      |

## 9. 인터페이스

### 9.1 REST

| 메서드 | 경로                     | 인증     | 설명                                          |
| ------ | ------------------------ | -------- | --------------------------------------------- |
| POST   | `/reconciliation/run`    | **없음** | 요청 본문의 expected/actual 두 집합을 즉석 대사 |
| GET    | `/reconciliation/demo`   | **없음** | 샘플 소스 대사(비-demo 프로파일에서는 실 소스로 폴백) |
| GET    | `/actuator/health`       | —        | 헬스(폴리글랏 중 유일하게 actuator 사용)      |

에러 매핑: 도메인 검증(음수 허용오차 등) 400 · 소스 장애 등 인프라 실패 500.

### 9.2 외부 호출 (소비)

| 대상               | 경로                                                             | role     |
| ------------------ | ---------------------------------------------------------------- | -------- |
| settlement-service | `GET /internal/recon/settlements?date&afterPaymentId&limit`       | EXPECTED |
| order-service      | `GET /internal/recon/captured-payments?date`                      | ACTUAL   |

타임아웃: connect 5s / read 30s.

### 9.3 이벤트

**없음.** Kafka 의존이 빌드에 없다.

## 10. 비기능 요구

| NFR   | 요구                     | 현재 상태                                                       |
| ----- | ------------------------ | --------------------------------------------------------------- |
| NFR-1 | 소스 병렬 fetch          | `async` + `Dispatchers.IO`(RestClient 는 블로킹)                |
| NFR-2 | 결과가 알림 가능하다     | `recon.last.run.epoch.seconds`·`recon.last.discrepancies`·`recon.last.records{role}`·`recon.runs{result}`·`recon.discrepancies{type}` |
| NFR-3 | 배치가 앱을 죽이지 않음  | fail-open catch                                                 |
| NFR-4 | 비루트 컨테이너          | Dockerfile non-root(`app`), JDK21                               |
| NFR-5 | JDK 21 고정              | 툴체인 21 + `jvmTarget` 21                                      |

## 11. 배치 (Asia/Seoul)

| 시각          | 작업                   | 설정 키                     |
| ------------- | ---------------------- | --------------------------- |
| 매일 19:00    | 전일분 정산 대사       | `app.reconciliation.cron`   |

## 12. 역산에서 드러난 격차

### G-1. 이 배치는 어떤 배포 환경에서도 돌지 않는다 ★

`docker-compose.yml` 에 `reconciliation-service` 정의가 **없고**, 게이트웨이 라우트도 없다. 즉 코드·테스트·
Dockerfile 은 온전하지만 **19:00 스케줄러가 실행되는 환경이 존재하지 않는다.** 정산 대사라는 이 서비스의
유일한 목적이 실제로는 한 번도 수행되지 않는 상태다. (settlement 자체의 `/admin/reconciliation` 경로는
게이트웨이에 노출돼 있어, 현재 대사는 settlement 내부 기능으로만 돈다.)

### G-2. `polyglot-services.md` 의 기술이 낡았다 ★

정본 문서는 이 서비스를 "MVP·skeleton (HTTP 소스 인터페이스만 — 실소스 빈 미등록)"이라 적고, 알려진 한계에도
"실소스 빈이 등록되지 않아 fetch 가 빈 리스트를 반환한다"고 쓰여 있다. **2026-07-30 변경으로 둘 다 사실이
아니다** — `SourceConfig.LiveSources` 가 실 HTTP 소스 2종을 등록하고, 스켈레톤 `return emptyList()` 는
페이지네이션 구현으로 교체됐다.

### G-3. `app.reconciliation.tolerance-krw` 설정이 읽히지 않는다

`application.yml` 에 `tolerance-krw: ${APP_RECONCILIATION_TOLERANCE_KRW:1}` 이 선언돼 있지만 이 값을 읽는
코드가 **없다.** 스케줄러는 `reconcileFromSources(sources, period)` 를 호출하고 허용오차는 인터페이스
기본값 `1` 이 되며, 컨트롤러는 자기 상수 `DEFAULT_TOLERANCE_KRW = 1` 을 쓴다. 운영자가 환경변수로 허용오차를
올려도 아무 일도 일어나지 않는다.

### G-4. `settlement-base-url` 기본 포트가 배포 관례와 다르다

기본값이 `http://settlement-service:8082` 인데, compose 네트워크에서 Java 서비스들은 컨테이너 내부 8080 으로
서비스한다(게이트웨이 env: `SETTLEMENT_SERVICE_URI: http://settlement-service:8080`). 8082 는 호스트 바인딩
포트다. G-1 때문에 지금은 드러나지 않지만, compose 에 올리는 순간 EXPECTED 소스가 연결 실패한다.

### G-5. `/reconciliation/demo` 가 프로덕션 프로파일에서 실 소스를 때린다

`demo` 엔드포인트는 이름이 `sample-*` 인 소스를 고르고, **없으면 role 이 EXPECTED/ACTUAL 인 소스로
폴백한다**(`ifEmpty`). 비-demo 프로파일에는 샘플이 없으므로 이 폴백이 항상 걸려, 인증 없는 GET 요청 하나가
settlement·order 내부 API 를 오늘 날짜로 전량 조회하게 만든다.

### G-6. 무인증 REST 표면

`POST /reconciliation/run` 과 `GET /reconciliation/demo` 모두 인증이 없다. 게이트웨이 미노출이 유일한
방어인데, G-5 와 결합하면 서비스 포트에 도달 가능한 위치에서 내부 대사 API 를 임의로 당길 수 있다.

### G-7. 대사 결과가 보존되지 않는다

리포트는 반환·로그·게이지로만 소비되고 저장되지 않는다. 게이지는 **마지막 실행값만** 갖는다(`recon.last.*`).
어제 불일치가 몇 건이었는지, 특정 businessKey 가 언제부터 어긋났는지 추적할 방법이 서비스 안에 없다.

### G-8. 커버리지 게이트가 없다

Java 모듈은 JaCoCo LINE 90% 를 강제하지만 이 standalone 빌드에는 커버리지 태스크가 없다. 테스트 4클래스가
엔진·서비스·컨트롤러·HTTP 소스를 덮지만, 스케줄러(`ReconciliationScheduler`)와 `SourceConfig` 프로파일 분기는
전용 테스트가 없다 — 즉 G-5 의 폴백과 BR-5 의 프로파일 규칙이 회귀 보호를 받지 못한다.

## 13. 추적 항목

| #   | 항목                                                    | 상태                  |
| --- | ------------------------------------------------------- | --------------------- |
| T-1 | compose 서비스 정의 — 배치가 실제로 돌 환경             | 없음 (G-1)            |
| T-2 | `polyglot-services.md` 상태 기술 갱신                   | 드리프트 (G-2)        |
| T-3 | `tolerance-krw` 를 실제로 주입                          | 사문화 (G-3)          |
| T-4 | `settlement-base-url` 기본 포트 정정(8082 → 8080)       | 불일치 (G-4)          |
| T-5 | `demo` 엔드포인트의 실 소스 폴백 차단                   | 폴백 상시 (G-5)       |
| T-6 | 대사 결과 영속화(이력 추적)                             | 없음 (G-7)            |
| T-7 | 스케줄러·프로파일 분기 테스트                           | 없음 (G-8)            |
