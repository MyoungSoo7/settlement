# P0 시드 갭 감사 — 12 AC × 기존 자산 매핑 (2026-07-21)

> 근거: 무결성 스위트 인벤토리 / 도메인 실태 / 테스트 커버리지 3중 조사 (병렬 Explore).
> 분류: **통과**(기능+검증기 존재) / **검증기 없음**(기능은 있음) / **부분**(기능 일부) / **기능 없음**(신규 구현 필요)

## 매핑표

| AC | 내용 요약 | 판정 | 근거 |
|----|-----------|------|------|
| 1 | 정산 확정→지급유형(IMMEDIATE/HOLDBACK_RELEASE)당 Payout 최대 1건 자동 생성 | **기능 없음** | `RequestPayoutUseCase.requestForSettlement()` 호출처 0건 (`PayoutService.java:80`), 확정 배치(`SettlementConfirmItemWriter.java:57-82`)는 Payout 미생성, 지급유형 필드 부재(`PayoutStatus.java:12`) |
| 2 | 중복·재시도·동시에도 Payout 중복 생성/이중 지급 없음 | **부분** | 1정산=1Payout 멱등은 견고(`uq_payouts_settlement` V1:87 + `findBySettlementId().orElseGet` + `PayoutServiceTest` UT). 유형당 1건 개념은 AC1 종속. 실 DB 동시 2스레드 Payout 생성 IT 없음 |
| 3 | IMMEDIATE 는 미해제 holdback 제외, HOLDBACK_RELEASE 는 미소비 보류 잔액만 | **기능 없음** | holdback 잔액 추적은 있음(`Settlement.consumeHoldbackForRefund():443`, `getImmediatePayoutAmount():463`) — 유형별 지급액 계산·생성 경로가 없음(AC1 종속) |
| 4 | adjustment 출처(REFUND/CHARGEBACK/PG_RECONCILIATION) + 균형 원장 분개 1:1 | **부분** | 출처 구분 견고(3 FK 배타 + `chk_adjustment_source_at_most_one` + 출처별 부분 UNIQUE). 원장 연동은 **환불만**(`AdjustSettlementForRefundService:87` 같은 tx Outbox). 차지백(`ChargebackService:106` "Phase 3")·PG대사(`ApplyReconciliationAdjustmentService:30` "NO ledger changes")는 원장 미연동 |
| 5 | DONE 정산 정정은 신규 adjustment+역분개로만 (append-only) | **통과(사실상)** | 도메인 throw(`Settlement.java:275,326`) + DB 트리거(`V20260715110200`) 이중 강제 + `SettlementInvariantTest`/`SettlementReconciliationClawbackTest` UT. 남은 것: 관통 IT + 트리거 직접 assert |
| 6 | event_id 누락·잘못된 payload → 유실 없이 PROCESSED/DUPLICATE/QUARANTINED 추적 결과 | **부분(기능 갭)** | payload 오류는 DLT 격리(`KafkaErrorHandlerConfig`). **event_id 누락은 경고 후 ack+skip = 유실**(`IdempotentEventConsumer.java:72-77`). QUARANTINED 상태/테이블 없음(DLT 로만) |
| 7 | 격리 이벤트 증거 보존 + 재처리 정확히 한 번 | **부분** | DLT replay 존재(`DlqReplayService.replay():88`, x-replay-count 5회 제한) + processed_events 멱등으로 exactly-once 재적용은 구조상 가능. 격리 원인·증거의 DB 보존 없음, quarantine 테스트 0건(DLQ 테스트로 대체) |
| 8 | 고정 데이터+단일 명령 E2E(캡처→정산→원장→Payout→송금) + 불변식 자동 단언 | **검증기 없음** | 전 구간 단일 시나리오 테스트 없음 — 구간별 IT 만 분산(`SettlementIdempotencyIntegrationTest`, `LedgerEndToEndIntegrationTest`, `PayoutExecutionResendGuardIT`) |
| 9 | 미생성 Payout·원장 없는 adjustment 탐지 보고서 + 멱등 백필 | **부분** | **탐지는 이미 존재**: INV-6 `settlementsWithoutPayout`(:158), INV-5 `missing`/`missingReverse`(:73,108), INV-8 `missingRefundIds`. **백필이 없음**(있는 건 PII 재암호화·차지백 링크 백필뿐). 백필 멱등 IT 는 PII 한정(`PayoutPiiReencryptionBackfillIT`) |
| 10 | 크래시 지점 재현해도 재시작 후 부분 반영 없이 수렴 | **검증기 없음(구조는 있음)** | 트랜잭셔널 아웃박스 + REQUIRES_NEW 마킹 분리 + 멱등 use case + `uq_ledger_reference_accounts` 로 구조적 수렴. 테스트는 `PayoutExecutionResendGuardIT` 1개 시나리오뿐 — 일반 크래시 지점 재현 IT 없음 |
| 11 | 이벤트 역순·중복·동시 도착 수렴 | **부분** | 중복 수렴 IT 강함(`SettlementIdempotencyIntegrationTest` 3단 방어 관통). **역순·동시 이벤트 도착 IT 없음**(DB 동시성은 `SettlementConcurrencyIntegrationTest` 로 별개 커버) |
| 12 | 송금 완료 Payout 은 채권·상계·회수 레코드로만 정정 | **기능 없음** | receivable/offset 개념 0건. COMPLETED 불변(`PayoutStatus:48`), 환수는 "Phase 3 / scope 밖" 주석만(`ChargebackService.java:37`, `ApplyReconciliationAdjustmentService.java:89`) |

## 결론

- **이미 절반쯤 커버**: AC5 통과급, AC2·4·9·11 은 기존 자산(무결성 스위트 7종 + 멱등 IT + Flyway 제약)이 상당 부분 담당.
- **진짜 기능 갭 3개** (코드 주석으로도 "의도적 미구현" 명시): ① 정산 확정→Payout 자동 생성 배선+지급유형(AC1·3) ② 차지백·PG대사 원장 연동(AC4 잔여) ③ 송금후 회수 채권/상계(AC12).
- **진짜 검증기 갭 3개**: E2E 하네스(AC8), 크래시 지점 재현(AC10), 역순·동시 도착 수렴(AC11). + event_id 누락 유실(AC6)은 작지만 실질 버그성 갭.
- 시드 분할: `seed-p0-1..6.yaml` 참조 (1→2→3→4 순 의존, 5·6 은 1~4 이후).
