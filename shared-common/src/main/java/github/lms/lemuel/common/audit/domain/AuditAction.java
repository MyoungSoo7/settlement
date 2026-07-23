package github.lms.lemuel.common.audit.domain;

public enum AuditAction {
    SETTLEMENT_CONFIRMED,
    SETTLEMENT_CANCELED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    REFUND_FAILED,
    USER_ROLE_CHANGED,
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    CASHFLOW_REPORT_ACCESSED,
    DLQ_INSPECTED,
    DLQ_REPLAYED,
    DLQ_PURGED,
    // 정산금 출금(Payout) 운영자 조작 — 실자금 이동이라 감사 추적 필수.
    PAYOUT_EXECUTED,
    PAYOUT_RETRIED,
    PAYOUT_CANCELED,
    // 송금 반송(bounce) 기록 + 정정계좌 재지급 — 실자금 재이동이라 감사 추적 필수.
    PAYOUT_BOUNCE_RECORDED,
    // 셀러 지급 계좌 레지스트리 등록·정정 — PII 계좌 변경의 감사 추적.
    SELLER_BANK_ACCOUNT_REGISTERED,
    // 카드사 분쟁(Chargeback) 결정 — 셀러 환수/면책 판단의 감사 추적.
    CHARGEBACK_ACCEPTED,
    CHARGEBACK_REJECTED,
    // PG 대사 승인 → 정산 역정산(clawback) 적용.
    RECON_ADJUSTMENT_APPLIED,
    // ledger_outbox FAILED 항목 운영자 일괄 재큐.
    LEDGER_OUTBOX_REQUEUED,
    // 격리(quarantined) 소비 이벤트 운영자 재처리 — 원본 토픽 republish, operator·quarantineId·event_id 추적.
    QUARANTINE_REPLAYED,
    // 이벤트드리븐 정산 생성(payment.captured 컨슈머, actor=system) — 정산금 발생 지점의 감사 추적.
    SETTLEMENT_CREATED,
    // 홀드백 해제 배치(actor=system) — 셀러 출금가능액 증가 시점의 감사 추적.
    HOLDBACK_RELEASED,

    // ── loan-service (선정산 LoanAdvance · 기업 CorporateLoan) 금전 액션 ──
    LOAN_ADVANCE_REQUESTED,
    LOAN_ADVANCE_DISBURSED,
    CORPORATE_LOAN_REQUESTED,
    CORPORATE_LOAN_REJECTED,
    CORPORATE_LOAN_DISBURSED,
    LOAN_REPAYMENT_APPLIED,

    // ── investment-service (투자주문) 금전 액션 ──
    INVESTMENT_ORDER_PLACED,
    INVESTMENT_ORDER_EXECUTED,
    INVESTMENT_ORDER_CANCELED,
    INVESTMENT_ORDER_REJECTED,

    // ── 과거 데이터 멱등 백필 — 누가·언제·몇 건 감사 추적 ──
    // 확정(DONE) 정산에서 누락된 Payout 을 append-only 로 신규 생성하는 백필 실행.
    PAYOUT_BACKFILL_EXECUTED,
    // 차지백·PG 대사 조정의 역분개 누락분을 ledger_outbox 에 적재하는 백필 실행.
    LEDGER_REVERSE_BACKFILL_EXECUTED
}
