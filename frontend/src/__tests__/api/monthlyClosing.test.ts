import { describe, it, expect, vi, beforeEach } from 'vitest';
import { monthlyClosingApi, type MonthlyClosingRun } from '@/api/monthlyClosing';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const run: MonthlyClosingRun = {
  periodYm: '2026-07',
  status: 'COMPLETED',
  triggeredBy: 'admin',
  startedAt: '2026-08-01T00:00:00Z',
  finishedAt: '2026-08-01T00:01:00Z',
  sellerCount: 12,
  settlementCount: 340,
  unmappedCount: 0,
  pendingCount: 0,
  totalGross: '100000000',
  totalRefunded: '2000000',
  totalCommission: '3500000',
  totalHoldback: '1000000',
  totalNet: '93500000',
  failureReason: null,
};

describe('monthlyClosingApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('마감 결과를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        run,
        sellers: [
          {
            sellerId: 1,
            settlementCount: 30,
            grossAmount: '10000000',
            refundedAmount: '0',
            commissionAmount: '350000',
            holdbackAmount: '0',
            netAmount: '9650000',
          },
        ],
      },
    });

    const result = await monthlyClosingApi.get('2026-07');

    expect(api.get).toHaveBeenCalledWith('/admin/monthly-closing/2026-07');
    expect(result.sellers).toHaveLength(1);
    expect(result.run.status).toBe('COMPLETED');
  });

  it('이력이 없는 달은 404 가 전파된다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 404 } });

    await expect(monthlyClosingApi.get('2026-06')).rejects.toMatchObject({
      response: { status: 404 },
    });
  });

  it('마감을 실행한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...run, status: 'RUNNING' } });

    const result = await monthlyClosingApi.run('2026-07');

    expect(api.post).toHaveBeenCalledWith('/admin/monthly-closing/2026-07/run');
    expect(result.status).toBe('RUNNING');
  });

  it('원장이 마감된 기간의 재실행은 409 로 막힌다', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 409 } });

    await expect(monthlyClosingApi.run('2026-06')).rejects.toMatchObject({
      response: { status: 409 },
    });
  });

  it('미매핑·미확정 건수는 그대로 노출한다 (집계에서 빠진 것을 숨기지 않는다)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { run: { ...run, unmappedCount: 2, pendingCount: 5 }, sellers: [] },
    });

    const result = await monthlyClosingApi.get('2026-07');

    expect(result.run.unmappedCount).toBe(2);
    expect(result.run.pendingCount).toBe(5);
  });
});
