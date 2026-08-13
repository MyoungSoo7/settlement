import api from './axios';

/**
 * 정합성 검증 콘솔 API — settlement-service `/admin/integrity/**` (전부 read-only GET).
 *
 * <p>모든 응답에 기계 판정 `ok` 와 사람이 읽는 `reasons` 가 들어 있다. 화면은 이 두 값을
 * 그대로 신뢰해 판정을 내리고, 숫자를 다시 비교해 자체 판정을 만들지 않는다 — 판정 로직이
 * 서버와 클라이언트 두 곳에 생기면 어긋나는 순간 어느 쪽이 맞는지 알 수 없게 된다.
 */

/** 8종 점검이 공통으로 갖는 판정부. */
export interface IntegrityVerdict {
  ok: boolean;
  reasons: string[];
}

export interface LedgerCompletenessReport extends IntegrityVerdict {
  targetDate: string;
  graceMinutes: number;
  confirmedSettlements: number;
  confirmedPaymentTotal: string;
  ledgerEntryRows: number;
  ledgerPostedTotal: string;
  missingSettlementIds: number[];
  pendingWithinGrace: number;
  amountMismatchedSettlementIds: number[];
  missingReverseAdjustmentIds: number[];
  ledgerOutboxPending: number;
  ledgerOutboxFailed: number;
  ledgerOutboxOldestPendingAgeSec: number;
}

export interface PayoutReconReport extends IntegrityVerdict {
  targetDate: string;
  confirmedSettlements: number;
  confirmedNetTotal: string;
  activePayouts: number;
  activePayoutTotal: string;
  completedPayouts: number;
  settlementsWithoutPayout: number[];
  overpaidPayouts: { payoutId: number; settlementId: number; payoutAmount: string; netAmount: string }[];
  duplicatePayoutSettlementIds: number[];
  overTotalSettlements: { settlementId: number; payoutTotal: string; netAmount: string }[];
}

export interface PayoutBounceReconReport extends IntegrityVerdict {
  totalBounces: number;
  resolvedBounces: number;
  unresolvedBounces: number;
  amountMismatches: {
    bounceId: number; payoutId: number; resolvedPayoutId: number;
    originalAmount: string; reissuedAmount: string;
  }[];
  reissuedWithSettlement: number[];
  orphanNullSettlementPayoutIds: number[];
}

export interface HoldbackStatusReport extends IntegrityVerdict {
  today: string;
  overdueCount: number;
  overdueAmountTotal: string;
  overdueSettlementIds: number[];
  totalHeld: string;
  totalReleased: string;
  lastReleasedAt: string | null;
}

export interface StuckStateReport extends IntegrityVerdict {
  thresholdMinutes: number;
  stuckSettlements: { id: number; status: string; since: string }[];
  overdueConfirmations: { id: number; status: string; since: string }[];
  stuckSendingPayouts: { payoutId: number; settlementId: number; amount: string; sentAt: string }[];
  stuckPgReconRuns: { id: number; status: string; since: string }[];
  stuckLedgerOutboxPending: number;
  ledgerOutboxFailed: number;
}

export interface RefundAdjustmentReport extends IntegrityVerdict {
  from: string;
  to: string;
  completedRefunds: number;
  completedRefundTotal: string;
  adjustedRefunds: number;
  missingRefundIds: number[];
  missingAmountTotal: string;
  truncated: boolean;
}

export interface ProcessedEventCount {
  consumerGroup: string;
  eventType: string;
  count: number;
}

export interface ProjectionDiffReport extends IntegrityVerdict {
  date: string;
  entity: string;
  checksumMatched: boolean;
  orderCount: number;
  orderAmountSum: string;
  projectionCount: number;
  projectionAmountSum: string;
  missingInProjectionCount: number;
  missingInProjectionIds: number[];
  missingInProjectionAmount: string;
  orphanInProjectionCount: number;
  orphanInProjectionIds: number[];
  amountMismatchCount: number;
  amountMismatches: { paymentId: number; orderAmount: string; projectionAmount: string }[];
  truncated: boolean;
}

export const integrityApi = {
  /** INV-5 원장 완전성 — 확정 정산·환불 조정 ↔ 분개 대조 */
  ledgerCompleteness: async (date: string, graceMinutes?: number): Promise<LedgerCompletenessReport> =>
    (await api.get<LedgerCompletenessReport>('/admin/integrity/ledger-completeness', {
      params: { date, graceMinutes },
    })).data,

  /** INV-6 지급 대사 — 확정 정산 ↔ payout 금액·중복 */
  payoutRecon: async (date: string): Promise<PayoutReconReport> =>
    (await api.get<PayoutReconReport>('/admin/integrity/payout-recon', { params: { date } })).data,

  /** INV-13 반송 재지급 대사 */
  payoutBounceRecon: async (): Promise<PayoutBounceReconReport> =>
    (await api.get<PayoutBounceReconReport>('/admin/integrity/payout-bounce-recon')).data,

  /** INV-7 홀드백 해제 기한 경과 */
  holdbackStatus: async (): Promise<HoldbackStatusReport> =>
    (await api.get<HoldbackStatusReport>('/admin/integrity/holdback-status')).data,

  /** INV-11 상태 체류 (SENDING payout 이 1순위 위험) */
  stuck: async (thresholdMinutes?: number): Promise<StuckStateReport> =>
    (await api.get<StuckStateReport>('/admin/integrity/stuck', { params: { thresholdMinutes } })).data,

  /** INV-8 지연 환불 조정 대사 */
  refundAdjustments: async (from: string, to: string): Promise<RefundAdjustmentReport> =>
    (await api.get<RefundAdjustmentReport>('/admin/integrity/refund-adjustments', {
      params: { from, to },
    })).data,

  /** INV-10 이벤트 회계 분자 — 소비 건수 (판정 없음, 원자료) */
  processedCount: async (from: string, to: string): Promise<ProcessedEventCount[]> =>
    (await api.get<ProcessedEventCount[]>('/admin/integrity/processed-count', {
      params: { from, to },
    })).data,

  /** INV-12 프로젝션 행 diff */
  projectionDiff: async (date: string, entity = 'payment', limit?: number): Promise<ProjectionDiffReport> =>
    (await api.get<ProjectionDiffReport>('/admin/integrity/projection-diff', {
      params: { date, entity, limit },
    })).data,
};
