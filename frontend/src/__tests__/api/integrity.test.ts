import { describe, it, expect, vi, beforeEach } from 'vitest';
import { integrityApi } from '@/api/integrity';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const verdict = { ok: true, reasons: [] as string[] };

describe('integrityApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('INV-5 원장 완전성 — graceMinutes 미지정이면 undefined 로 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, targetDate: '2026-08-01' } });

    const result = await integrityApi.ledgerCompleteness('2026-08-01');

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/ledger-completeness', {
      params: { date: '2026-08-01', graceMinutes: undefined },
    });
    expect(result.ok).toBe(true);
  });

  it('INV-5 — graceMinutes 를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, targetDate: '2026-08-01' } });

    await integrityApi.ledgerCompleteness('2026-08-01', 30);

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/ledger-completeness', {
      params: { date: '2026-08-01', graceMinutes: 30 },
    });
  });

  it('INV-6 지급 대사', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { ...verdict, ok: false, reasons: ['중복 지급 1건'], duplicatePayoutSettlementIds: [7] },
    });

    const result = await integrityApi.payoutRecon('2026-08-01');

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/payout-recon', {
      params: { date: '2026-08-01' },
    });
    expect(result.ok).toBe(false);
    expect(result.reasons).toContain('중복 지급 1건');
  });

  it('INV-13 반송 재지급 대사는 파라미터가 없다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, totalBounces: 0 } });

    await integrityApi.payoutBounceRecon();

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/payout-bounce-recon');
  });

  it('INV-7 홀드백 상태', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, overdueCount: 0 } });

    const result = await integrityApi.holdbackStatus();

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/holdback-status');
    expect(result.overdueCount).toBe(0);
  });

  it('INV-11 상태 체류 — 임계 분을 넘길 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, thresholdMinutes: 60 } });

    await integrityApi.stuck(60);

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/stuck', {
      params: { thresholdMinutes: 60 },
    });
  });

  it('INV-11 — 임계 미지정 시 서버 기본값에 맡긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, thresholdMinutes: 30 } });

    await integrityApi.stuck();

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/stuck', {
      params: { thresholdMinutes: undefined },
    });
  });

  it('INV-8 지연 환불 조정 — 기간 파라미터', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, missingRefundIds: [] } });

    await integrityApi.refundAdjustments('2026-08-01', '2026-08-07');

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/refund-adjustments', {
      params: { from: '2026-08-01', to: '2026-08-07' },
    });
  });

  it('INV-10 이벤트 소비 건수는 판정 없이 원자료를 돌려준다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [{ consumerGroup: 'settlement', eventType: 'payment.captured', count: 12 }],
    });

    const result = await integrityApi.processedCount('2026-08-01', '2026-08-07');

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/processed-count', {
      params: { from: '2026-08-01', to: '2026-08-07' },
    });
    expect(result[0].count).toBe(12);
  });

  it('INV-12 프로젝션 diff — entity 기본값은 payment', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, entity: 'payment' } });

    await integrityApi.projectionDiff('2026-08-01');

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/projection-diff', {
      params: { date: '2026-08-01', entity: 'payment', limit: undefined },
    });
  });

  it('INV-12 — entity·limit 을 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...verdict, entity: 'order' } });

    await integrityApi.projectionDiff('2026-08-01', 'order', 100);

    expect(api.get).toHaveBeenCalledWith('/admin/integrity/projection-diff', {
      params: { date: '2026-08-01', entity: 'order', limit: 100 },
    });
  });
});
