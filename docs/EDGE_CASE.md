# EDGE_CASE.md — 엣지케이스 방어 체계 (정산·대출·투자)

> settlement · loan · investment 3개 금융 도메인을 **15개 엣지케이스 항목**으로 감사 → 보강 → 3인 독립 패널
> 채점하는 루프를 5라운드 수행한 결과의 정리. 완료 판정은 문서가 아니라 **기계 게이트**가 정답이다:
> 전 모듈 `./gradlew clean build` (테스트 + JaCoCo LINE 90%) GREEN + 패널 중앙값 평균 **9.93/10**.
>
> - 캠페인 커밋: PR [#163](https://github.com/MyoungSoo7/settlement/pull/163) (2026-07-17 main 반영)
> - 채점 규율: 감점은 "결함 파일:라인 + 실패 시나리오"를 지목해야만 유효 (무지목 감점 무효)

## 최종 점수표 (3인 패널 중앙값, 1라운드 → 5라운드)

| # | 항목 | 점수 | # | 항목 | 점수 |
|---|------|:---:|---|------|:---:|
| 1 | 입력값 검증 | 8→**10** | 9 | 장애 복구 | 8→**10** |
| 2 | 데이터 경계값 | 8→**10** | 10 | 서비스 간 통신 | 7→**10** |
| 3 | 금액 정밀도·라운딩 | 9→**10** | 11 | 메시지 처리 | 8→**10** |
| 4 | 상태 전이 | 9→**10** | 12 | 운영·배포 | 8→**10** |
| 5 | 중복 요청·멱등성 | 9→**10** | 13 | 인증·권한 | 6→**10** |
| 6 | 동시성 | 8→**10** | 14 | 시간·타임존 | 5→**9** |
| 7 | 트랜잭션 | 9→**10** | 15 | 감사·관측성 | 5→**10** |
| 8 | 데이터 정합성 | 8→**10** | | **평균** | **7.67→9.93** |

## 루프가 잡아낸 실버그 4건 (전부 수정 + 회귀 테스트)

| 버그 | 실손 시나리오 | 수정 |
|------|--------------|------|
| **loan IDOR** | 요청 바디 sellerId 신뢰 → 타인 명의 대출 신청·타인 대출 전체 조회 | sellerId 를 바디에서 제거, JWT 주체(userId) 파생 + `requireSelf` 소유권 대조(403). 회귀: 타인 403/미인증 403 테스트 |
| **정산일 계산** | T+N 기준이 결제일이 아닌 컨슈머 처리시각 → 지연 소비·백필 시 같은 결제가 다른 정산일 | `payment.captured` 의 `capturedAt`(KST 변환) 기준으로 수정, 폴백은 KST now + 로그 |
| **투자 재원 이중집행 (write-skew)** | 같은 셀러 두 주문 동시 집행 → 둘 다 미집행 합계를 관측해 확정 정산금 초과 집행 | 셀러 재원 행 `SELECT … FOR UPDATE` 직렬화(신청·집행 양 경로) + `investment_orders @Version` + 동시집행 IT(정확히 1승) |
| **멱등 가드 500** | 유니크 위반을 catch 해도 REQUIRES_NEW 가 rollback-only → 중복 요청이 409 아닌 500 | `INSERT … ON CONFLICT DO NOTHING` 원자 upsert(영향 행 수 판정)로 전환 — **실DB IT 가 발견** (목 테스트는 못 잡던 버그) |

---

## 항목별 방어 체계

### 1. 입력값 검증
- **웹 계층**: 전 커맨드 DTO Bean Validation — 금액 `@Digits(integer=17, fraction=2)`·`@Positive`, 기간 `@Max`(선정산 365/기업 3650/상환 시뮬 600개월), 종목코드 `@Pattern(\d{6})`. 검색 API 는 `@Validated` + `page @Min(0)`, `size @Min(1) @Max(200)`, 날짜 `LocalDate` 바인딩 + 역전 400 (`SettlementSearchController`) — size=0 나눗셈·음수 SQL 오류·무상한 스캔(DoS) 차단.
- **도메인 이중 검증**: 웹을 우회해도 도메인 팩토리가 재검증 (`InvestmentOrder.request`, `Settlement.createFromPayment`, `CorporateLoan.request`, `RepaymentSchedule.of`).
- **에러 일관성**: 도메인 불변식 예외가 전부 `BusinessException` 계열 → `GlobalExceptionHandler` 단일 매핑, 500 누수 없음.

### 2. 데이터 경계값
- 누적 환불 > 결제액 차단, net ≤ 0 → CANCELED (`Settlement`), 신용점수 0/100 경계, 수수료 등급 구간 경계(AT_LEAST/AT_MOST 선언적 밴드), 호가단위 FLOOR 경계.
- 경계 테스트로 고정: 검색 size 0/-1/201·page -1·날짜 역전/형식오류, 금액 17자리 상한, KST 자정 직전/직후, 월말, 윤년(2028-02-29), 설·추석 연휴 통째 스킵 (`BusinessDayCalculatorTest` 등).

### 3. 금액 정밀도·라운딩
- **금액 경로 double/float 0** — `guard.mjs` 가 기계 차단. 관측용(메트릭 게이지·페이지 계산)만 예외.
- `Money` VO(shared-common)가 scale 2 HALF_UP 단일 초크포인트. 도메인 진입 시 정규화(정산·대출 원금·투자 금액) → 입력·판정·저장·응답 기준 일치(소수 3자리 round-trip 테스트).
- 중간 계산 고정밀(DECIMAL128) → 최종 라운딩, 상환 스케줄 마지막 회차 잔차 흡수(원금합 = 신청원금 정확 일치). DB `numeric(19,2)` 정합. `commission_rate` 정산 시점 스냅샷 영구 보존.

### 4. 상태 전이
- 7개 상태머신 전부 `canTransitionTo()` **단일 출처** + 도메인 메서드가 강제: Settlement, Payout(REQUESTED→SENDING→COMPLETED/FAILED), Ledger(PENDING→POSTED→REVERSED, POSTED 불변·역분개만), Chargeback, LoanAdvance(OVERDUE→REPAID/WRITTEN_OFF 포함 — 死상태 해소), CorporateLoan, InvestmentOrder.
- 비정상 전이는 타입 예외(`Invalid{X}StateException`) — generic IAE 금지(OO 게이트). 허용·금지 전이 전수 테스트.

### 5. 중복 요청·멱등성
- **3단 멱등 방어**: ① `outbox_events.event_id` UNIQUE → ② 컨슈머 `processed_events(consumer_group, event_id)` PK (`IdempotentEventConsumer` 템플릿 강제) → ③ 도메인 UNIQUE(`settlements.payment_id`, `payouts.settlement_id`, `loan_repayments.settlement_id`, `seller_funding_view.settlement_id`, 원장 이중전기 UNIQUE).
- **수동 REST Idempotency-Key**: 운영자 더블클릭 방어 — settlement `ManualIdempotencyGuard`(payout retry/cancel·chargeback), investment `InvestmentManualIdempotencyGuard`(place/execute/cancel). 키 PK 를 `INSERT ON CONFLICT DO NOTHING` 원자 upsert 로 선점, 중복 409. **실DB 관통 IT**(순차 중복·동시 2스레드 1승) 포함.

### 6. 동시성
- 환불 vs 정산 확정: 정산 행 비관적 락(PESSIMISTIC_WRITE). 배치 vs 수동 이중확정 차단.
- payout 이중송금: `claimForSending` — `UPDATE … WHERE status='REQUESTED'` 원자 선점 + `@Version`.
- 투자 재원: 셀러 재원 행 FOR UPDATE 직렬화(양 경로) + `@Version` + `InvestmentConcurrencyIntegrationTest`(동시 2주문 → 정확히 1 EXECUTED·1 REJECTED, 집행합 ≤ 재원).
- 스케줄러 중복 실행: 전 배치 `@SchedulerLock`(ShedLock). outbox 멀티워커: `FOR UPDATE SKIP LOCKED` + 리스(`OutboxClaimConcurrencyIT`).

### 7. 트랜잭션
- Outbox INSERT = 비즈니스 트랜잭션 **동일 커밋**(원자성), Kafka 발행은 트랜잭션 밖(커넥션 점유 최소·at-least-once).
- **payout 2-phase** (`PayoutTxSteps`, 각 REQUIRES_NEW): ① SENDING 선점 짧은 tx 커밋 → ② **tx 밖** 펌뱅킹 send → ③ 결과 별도 tx 확정. send 후 크래시 시 SENDING 잔류 → 배치 재집 없음 = **재송금 창 제거** (`PayoutExecutionResendGuardIT` "재송금 0" 실DB 증명). 잔류 SENDING 은 integrity stuck 감시가 포착.
- 재원부족 거절: `noRollbackFor=InsufficientFundingException` — REJECTED 확정이 예외와 함께 커밋(롤백 증발 방지).

### 8. 데이터 정합성
- **원장 구성적 균형**: `LedgerEntry.balancedPairForSettlement` — 차1·대1·`payment=net+commission` 불변식을 팩토리가 강제, 반쪽 전표 삽입 불가. POSTED 수정 금지(역분개만).
- **Integrity Suite 상시 가동**: 6종 불변식 체크(`/admin/integrity`) + `IntegrityMonitorScheduler`(일 1회, 체크별 fail-soft) + `DailyReconciliationScheduler`(일일 대사, order 다운 시 run 만 스킵).
- **Phase C 행 단위 프로젝션 대사**: 체크섬(count·금액합·정렬 id md5) 1차 스크리닝 → 불일치 날만 keyset 페이지로 키 diff → **누락/고아/금액불일치 id 특정** (`ProjectionReconciliationService` + order `/internal/recon/payment-keys*`). 프로젝션 뷰 행 1건 삭제를 실DB IT 로 검출 검증. cross-DB 조인 0 (ADR 0020 유지).
- `ledger_outbox` FAILED 종착분은 admin 재큐(`/admin/outbox/ledger/requeue-failed`, 상한 500건, 감사 기록).

### 9. 장애 복구
- **컨슈머 DLT 3서비스 동형** (`KafkaErrorHandlerConfig`): 일시 예외 FixedBackOff(2s×3)→DLT, 포이즌(역직렬화/IllegalArgument/IllegalState) **즉시 DLT**, event_id·traceparent 헤더 패스스루(재생 멱등), acks=all+idempotence. Spring 기본(조용한 skip = 유실)을 명시 대체 — settlement.confirmed 유실로 인한 상환차감 누락(실손) 경로 제거.
- outbox: 실패 재시도(10회) → FAILED 시 정확히 1회 DLQ, 워커 사망 시 리스 만료 회수, DLQ replay 루프 가드(x-replay-count).
- 러너북 10종(`runbook`) + `incident-runbooks` 스킬.

### 10. 서비스 간 통신
- `OrderReconClient`: connect 2s/read 5s 타임아웃(무한 hang 차단) + 타임아웃·5xx 1회 재시도(4xx 즉시 실패) + `OrderReconUnavailableException` 번역 — 대사 run 명시적 실패(스킵+ERROR), 핫패스 무영향.
- 내부 API 는 `X-Internal-Api-Key` 공유 시크릿, 운영은 fail-closed(§12). 양측 자기 DB 만 읽는 self-totals 설계라 상대 다운이 정산 경로에 전파되지 않음.

### 11. 메시지 처리
- **이벤트 계약-as-code** (ADR 0024): cross-service 12토픽 JSON Schema + 정본 샘플 단일 출처, 프로듀서·컨슈머 **양방향 계약 테스트** + 픽스처 self-test 12토픽 전수 — 드리프트 빌드 시점 차단.
- 순서 역전(payment.captured 가 order.created 선행) ECST 흡수, 리밸런싱 중복 소비는 MANUAL_IMMEDIATE + processed_events 멱등으로 무해.

### 12. 운영·배포
- **fail-closed**: `application-prod.yml`(settlement·loan·investment)이 `app.security.internal-key-required=true` 고정 — 키 미주입 운영 기동 시 내부 API 401. `JWT_SECRET` 기본값 없음(미설정 = 기동 실패, ≥32바이트).
- Flyway `validate-on-migrate: true`(체크섬 검증), graceful shutdown + liveness/readiness, CI: 변경 모듈 build = 테스트 + JaCoCo LINE 90% 게이트 + harness-guard.

### 13. 인증·권한
- **IDOR 원천 차단**(가드레일): 셀러 리소스 식별자는 요청이 아니라 **JWT 주체(userId)에서 파생** — loan(신청 바디 sellerId 제거·조회 requireSelf 403), investment(place/execute/cancel/조회 전부 소유권 대조), 기업대출 목록은 ADMIN/MANAGER 전체·USER 본인만.
- JWT HS256 고정(`verifyWith(SecretKey)` — alg 혼동 차단), 위조·만료·누락 전부 401 수렴(오라클 없음), 실행(disburse)은 ADMIN 경로 게이트.

### 14. 시간·타임존 — 9/10 (유일한 잔여 한계 항목)
- **Asia/Seoul Clock 주입 3서비스**(TimeConfig) — 응용 서비스가 `now(clock)` 사용, 도메인은 시각 파라미터 수령 → UTC JVM 에서도 KST 자정 off-by-one 없음(고정 Clock 경계 테스트).
- 정산일 = **결제시각(capturedAt) 기준** T+N (백필 재현성). 스케줄러 cron zone="Asia/Seoul" + 본문 now 일치(홀드백 하루 지연 해소).
- 영업일 캘린더: 주말 + 양력 공휴일 + **2026~2030 음력 명절·대체공휴일**(2028 추석∩개천절 중첩 대체 포함) 등재, `app.settlement.extra-holidays` 설정으로 임시공휴일을 **재배포 없이** 주입.
- **알려진 한계**: 2031년 이후 음력 명절 미등재 → 영업일 오판 가능(테스트로 한계 명시). 완화: extra-holidays 주입. 후속 과제: 등재범위 만료 사전 경보 또는 외부 캘린더 연동.

### 15. 감사·관측성
- **돈이 움직이는 전 액션 감사로그**(audit_logs): 정산 생성(SETTLEMENT_CREATED)·확정(잡 요약)·홀드백 해제, payout 배치 건별 집행(actor=system)+수동 retry/cancel, chargeback 승인/거절, PG 대사 조정, 대출 신청/실행/상환, 투자 신청/집행/취소(+실패 REJECTED 기록). AuditLogger 는 REQUIRES_NEW + 예외 흡수 — 감사 실패가 본 흐름을 깨지 않음. 감사 발생 회귀 테스트.
- **메트릭**: 대사 불일치(축별)·정산 확정 건수/금액·ledger_outbox FAILED 게이지·outbox lag·프로젝션 드리프트·대출 신청/실행/거절·투자 집행/재원부족 거절·DLT 발행 카운터.
- **opssignal 관제 전파**(fire-and-forget, 절대 throw 금지): payout 실패·outbox FAILED 확정·대사 불일치 → operation-service 신호.

---

## 재검증 방법

```bash
# 전 모듈 게이트 (테스트 + JaCoCo LINE 90%) — Docker 필요(Testcontainers IT)
./gradlew clean build

# 핵심 시나리오 IT (settlement / investment)
./gradlew :settlement-service:test --tests "*PayoutExecutionResendGuardIT" --tests "*IntegrityPhaseCIntegrationTest"
./gradlew :investment-service:test --tests "*InvestmentConcurrencyIntegrationTest" --tests "*InvestmentManualIdempotencyIntegrationTest"
```

> 도메인 규칙 상세는 `*-rules` 스킬(settlement-domain·loan-domain·investment-domain), 이벤트·멱등 코드 규칙은
> `idempotency-and-events` 스킬, 온콜 진단은 `incident-runbooks` 스킬 참조.
