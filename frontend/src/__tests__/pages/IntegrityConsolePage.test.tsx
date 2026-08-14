import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import IntegrityConsolePage from '@/pages/settlement/IntegrityConsolePage';
import { integrityApi } from '@/api/integrity';

vi.mock('@/api/integrity', () => ({
  integrityApi: {
    ledgerCompleteness: vi.fn(),
    payoutRecon: vi.fn(),
    payoutBounceRecon: vi.fn(),
    holdbackStatus: vi.fn(),
    stuck: vi.fn(),
    refundAdjustments: vi.fn(),
    processedCount: vi.fn(),
    projectionDiff: vi.fn(),
  },
}));

const mocked = vi.mocked(integrityApi);

const okLedger = {
  targetDate: '2026-08-12', graceMinutes: 30, confirmedSettlements: 3,
  confirmedPaymentTotal: '300000', ledgerEntryRows: 6, ledgerPostedTotal: '300000',
  missingSettlementIds: [], pendingWithinGrace: 0, amountMismatchedSettlementIds: [],
  missingReverseAdjustmentIds: [], ledgerOutboxPending: 0, ledgerOutboxFailed: 0,
  ledgerOutboxOldestPendingAgeSec: 0, ok: true, reasons: [],
};

const violatedPayout = {
  targetDate: '2026-08-12', confirmedSettlements: 2, confirmedNetTotal: '200000',
  activePayouts: 3, activePayoutTotal: '250000', completedPayouts: 1,
  settlementsWithoutPayout: [],
  overpaidPayouts: [{ payoutId: 77, settlementId: 12, payoutAmount: '150000', netAmount: '100000' }],
  duplicatePayoutSettlementIds: [12],
  overTotalSettlements: [{ settlementId: 12, payoutTotal: '250000', netAmount: '100000' }],
  ok: false,
  reasons: ['과다 지급 1건 — payout 금액이 정산 net 을 초과'],
};

const emptyVerdict = { ok: true, reasons: [] };

beforeEach(() => {
  vi.clearAllMocks();
  mocked.ledgerCompleteness.mockResolvedValue(okLedger);
  mocked.payoutRecon.mockResolvedValue(violatedPayout);
  mocked.payoutBounceRecon.mockResolvedValue({
    totalBounces: 0, resolvedBounces: 0, unresolvedBounces: 0, amountMismatches: [],
    reissuedWithSettlement: [], orphanNullSettlementPayoutIds: [], ...emptyVerdict,
  });
  mocked.holdbackStatus.mockResolvedValue({
    today: '2026-08-13', overdueCount: 0, overdueAmountTotal: '0', overdueSettlementIds: [],
    totalHeld: '50000', totalReleased: '10000', lastReleasedAt: null, ...emptyVerdict,
  });
  mocked.stuck.mockResolvedValue({
    thresholdMinutes: 30, stuckSettlements: [], overdueConfirmations: [],
    stuckSendingPayouts: [], stuckPgReconRuns: [], stuckLedgerOutboxPending: 0,
    ledgerOutboxFailed: 0, ...emptyVerdict,
  });
  mocked.refundAdjustments.mockResolvedValue({
    from: '2026-08-06', to: '2026-08-12', completedRefunds: 0, completedRefundTotal: '0',
    adjustedRefunds: 0, missingRefundIds: [], missingAmountTotal: '0', truncated: false, ...emptyVerdict,
  });
  mocked.projectionDiff.mockResolvedValue({
    date: '2026-08-12', entity: 'payment', checksumMatched: true, orderCount: 5,
    orderAmountSum: '500000', projectionCount: 5, projectionAmountSum: '500000',
    missingInProjectionCount: 0, missingInProjectionIds: [], missingInProjectionAmount: '0',
    orphanInProjectionCount: 0, orphanInProjectionIds: [], amountMismatchCount: 0,
    amountMismatches: [], truncated: false, ...emptyVerdict,
  });
  mocked.processedCount.mockResolvedValue([
    { consumerGroup: 'settlement-payment', eventType: 'lemuel.payment.captured', count: 12 },
  ]);
});

describe('IntegrityConsolePage', () => {
  it('진입하면 8종을 자동으로 한 번에 순회한다', async () => {
    render(<IntegrityConsolePage />);

    await waitFor(() => expect(mocked.ledgerCompleteness).toHaveBeenCalledTimes(1));
    expect(mocked.payoutRecon).toHaveBeenCalledTimes(1);
    expect(mocked.payoutBounceRecon).toHaveBeenCalledTimes(1);
    expect(mocked.holdbackStatus).toHaveBeenCalledTimes(1);
    expect(mocked.stuck).toHaveBeenCalledTimes(1);
    expect(mocked.refundAdjustments).toHaveBeenCalledTimes(1);
    expect(mocked.projectionDiff).toHaveBeenCalledTimes(1);
    expect(mocked.processedCount).toHaveBeenCalledTimes(1);
  });

  it('서버 판정(ok)을 그대로 배지로 보여 준다 — 정상 7 / 위반 1', async () => {
    render(<IntegrityConsolePage />);

    await waitFor(() => expect(screen.getByText('위반 1건')).toBeInTheDocument());
    expect(screen.getAllByText('정상')).toHaveLength(6); // ok=true 6종 (이벤트 소비는 판정 없음)
    expect(screen.getByText('위반')).toBeInTheDocument();
  });

  it('판정 없는 원자료(이벤트 소비 건수)는 위반으로 세지 않는다', async () => {
    render(<IntegrityConsolePage />);

    await waitFor(() => expect(screen.getByText('원자료')).toBeInTheDocument());
    expect(screen.getByText('settlement-payment')).toBeInTheDocument();
    // '12' 는 날짜 문자열에도 섞이므로 표의 셀로 좁혀 찾는다
    expect(screen.getByRole('cell', { name: '12' })).toBeInTheDocument();
  });

  it('서버가 준 reasons 를 그대로 노출한다', async () => {
    render(<IntegrityConsolePage />);

    await waitFor(() =>
      expect(screen.getByText('과다 지급 1건 — payout 금액이 정산 net 을 초과')).toBeInTheDocument());
  });

  it('위반 상세(과다 지급 payout·중복 정산 id)를 드릴다운으로 보여 준다', async () => {
    render(<IntegrityConsolePage />);

    await waitFor(() => expect(screen.getByText(/payout #77/)).toBeInTheDocument());
    expect(screen.getByText('중복 payout 정산 (1)')).toBeInTheDocument();
  });

  it('한 점검이 실패해도 나머지 판정은 계속 보인다', async () => {
    mocked.holdbackStatus.mockRejectedValue(new Error('boom'));

    render(<IntegrityConsolePage />);

    await waitFor(() => expect(screen.getByText('조회 실패')).toBeInTheDocument());
    // 실패한 카드 하나를 빼고도 원장 완전성 판정은 정상적으로 표시된다
    expect(screen.getAllByText('정상').length).toBeGreaterThanOrEqual(5);
  });
});
