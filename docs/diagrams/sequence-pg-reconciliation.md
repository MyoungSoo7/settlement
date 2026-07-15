# 시퀀스 — PG 정산파일 대사 (자동 차액 보정)

> 매일 PG 사가 보내주는 정산 CSV 와 내부 결제 원장을 비교 → 차액 분류 → 일부 자동 보정.

```mermaid
sequenceDiagram
    autonumber
    actor Op as 운영자
    participant API as PgReconciliation<br/>Controller
    participant Svc as ReconcilePgFile<br/>Service
    participant Parser as CsvPgFileParser<br/>Adapter
    participant Internal as InternalPaymentsForRecon<br/>JdbcAdapter (→ OrderReconClient)
    participant Match as PgReconciliation<br/>Matcher
    participant DB as pg_reconciliation_*

    Op->>API: POST /admin/pg-reconciliation/files<br/>(provider, date, csv)
    API->>Svc: reconcile(...)
    Svc->>Parser: parse(InputStream)
    Parser-->>Svc: List<PgTransactionRow>

    Svc->>Internal: loadByCapturedDate(date)
    Note over Internal: settlement 가 order DB 를 직접 읽지 않고<br/>OrderReconClient 로 order 내부 API(/internal/recon)<br/>호출 → CAPTURED/REFUNDED 합계 수신 (ADR 0020, cross-DB 0)
    Internal-->>Svc: List<InternalPaymentRow>

    Svc->>Match: match(pgRows, internalRows)
    Note over Match: 도메인 순수 로직 (Spring 의존성 0)

    rect rgb(240, 250, 240)
        Note over Match: ── 5종 분류 ──
        Match->>Match: pgKey 양쪽 동일 + amount 동일 → MATCHED
        Match->>Match: 차이 < 1원 → ROUNDING_DIFF (자동 보정)
        Match->>Match: 차이 ≥ 1원 → AMOUNT_MISMATCH (검토)
        Match->>Match: PG 에만 → MISSING_INTERNAL (위험)
        Match->>Match: 내부에만 → MISSING_PG
        Match->>Match: PG 중복 → DUPLICATE
    end

    Match-->>Svc: MatchResult(matched, discrepancies)

    Svc->>DB: INSERT pg_reconciliation_runs<br/>+ pg_reconciliation_discrepancies
    Note over DB: ROUNDING_DIFF 는 status=AUTO_CORRECTED<br/>나머지는 status=PENDING

    Svc-->>API: ReconciliationRun
    API-->>Op: run summary

    Op->>API: GET /admin/pg-reconciliation/runs/{id}
    API-->>Op: PENDING discrepancy list

    alt 운영자 승인
        Op->>API: POST /discrepancies/{id}/approve
        Note over API: 후속: SettlementAdjustment 생성<br/>(역정산 트리거)
    else 운영자 거절
        Op->>API: POST /discrepancies/{id}/reject<br/>(reason 필수)
        Note over API: status=REJECTED + 사유 영구 기록
    end
```

## 5종 차이 분류 매트릭스

| Type | 양측 존재 | 금액 차이 | 처리 정책 | 운영 영향 |
|------|-----------|-----------|-----------|-----------|
| **MATCHED** | ✅ | 0원 | 통과 | 없음 |
| **ROUNDING_DIFF** | ✅ | < 1원 | 자동 보정 (`AUTO_CORRECTED`) | 없음 |
| **AMOUNT_MISMATCH** | ✅ | ≥ 1원 | `PENDING` → 운영자 검토 | 매월 1~2건 정상 |
| **MISSING_INTERNAL** | PG만 | — | `PENDING` ⚠️ | **거래 누락 의심 — 가장 위험** |
| **MISSING_PG** | 내부만 | — | `PENDING` | PG 정산 지연 가능성 |
| **DUPLICATE** | ✅ + 중복 | — | `PENDING` | 이중 청구 의심 |

## 운영 메트릭

Prometheus 카운터 (Grafana 알람 연계):
- `pg.reconciliation.discrepancies{provider, type}` — 발견 건수
- `pg.reconciliation.discrepancies.approved` — 운영자 승인 누적
- `pg.reconciliation.discrepancies.rejected` — 운영자 거절 누적

```promql
# 매일 MISSING_INTERNAL 발생 시 즉시 알람
sum by (provider) (rate(pg_reconciliation_discrepancies_total{type="MISSING_INTERNAL"}[1d])) > 0
```
