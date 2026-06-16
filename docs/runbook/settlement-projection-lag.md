# Runbook — settlement 프로젝션 복제 지연 (ADR 0020 Phase 5.6)

> 대상 알림: `SettlementProjectionLagHigh`(p95>30s) · `SettlementProjectionLagCritical`(p95>120s) ·
> `SettlementProjectionStalled`(30분 반영 0).
> 배경: settlement 는 order 도메인 이벤트(Kafka)를 소비해 `settlement_db` 의 `settlement_*_view`
> 프로젝션을 채운다(CQRS). 물리 분리(Phase 4) 후 이 경로의 지연이 조회/리포팅 정합성에 직결된다.

## 메트릭

| 지표 | 의미 |
|---|---|
| `settlement_projection_lag_seconds{type}` | 발행→반영 end-to-end 지연 타이머 (Kafka record ts 기준). p95/p99 추적 |
| `settlement_projection_applied_total{type}` | type(payment/order/user/product) 별 반영 건수 |
| `settlement_projection_rows{view}` | 각 `*_view` 현재 행 수 (opslab 원천과 대조) |

대시보드: Grafana **"Lemuel — Settlement Projection"** (`lemuel-settlement-projection`).

## 영향 범위

- **정산 *생성* 은 영향 없음** — 이벤트 동봉 데이터(Event-Carried State Transfer, Phase 1)만으로 동작.
- **조회/리포팅/검색** 이 stale 해질 수 있음 — `*_view` 기반 QueryDSL·ES·cashflow 리포트.

## 진단 순서

1. **어느 type 이 지연되나** — 대시보드 lag p95 패널에서 type 확인 (payment/order/user/product).
2. **컨슈머 살아있나** — 컨슈머 그룹 `lemuel-settlement`(및 `lemuel-settlement-payment-view`) lag 확인:
   ```bash
   rpk group describe lemuel-settlement
   ```
   - 그룹이 비어있거나 멤버 0 → 컨슈머 정지/구독 해제. settlement-service 헬스·로그 확인.
3. **브로커/파티션 적체** — 토픽 lag 가 크면 프로듀서(order) 폭주 또는 컨슈머 처리율 부족.
4. **settlement_db 부하** — `hikaricp_connections_active`, 슬로우 쿼리. upsert 가 느리면 반영 지연.
5. **DLT 동반 상승** — `settlement_kafka_dlt_published_total` rate 가 함께 오르면 독성 메시지로
   파티션 stall 가능 → `GET /admin/dlq/inspect` 로 페이로드/예외 확인(ADR 0017).

## 조치

| 원인 | 조치 |
|---|---|
| 컨슈머 정지 | settlement-service 재기동 / 스케일 업. 재구독 후 lag 소진 관찰 |
| 처리율 부족 | 컨슈머 concurrency 상향(`app.kafka.consumer.concurrency`), 파티션 수 점검 |
| DB 병목 | HikariCP 풀·인덱스 점검, settlement_db 리소스 상향 |
| 독성 메시지 stall | DLT inspect → 페이로드 패치/컨슈머 핫픽스 → `POST /admin/dlq/replay` |
| 프로젝션 누락(행 수 드리프트) | `POST /admin/settlement-projection/backfill` 로 이벤트 재발행(멱등) — Phase 5.2 대사의 자가치유 훅 |

## 관련

- [ADR 0020 Phase 5](../adr/0020-order-settlement-db-split.md#phase-5--하드닝-잔여-작업)
- [DB 컷오버 런북](settlement-db-cutover.md)
- [Outbox 적체 런북](outbox-backlog.md)
