import { describe, it, expect, vi, beforeEach } from 'vitest';
import { recoveryApi } from '@/api/recovery';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

describe('recoveryApi.bySeller', () => {
  beforeEach(() => vi.resetAllMocks());

  it('셀러의 미상계 잔액과 근거를 한 번에 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        sellerId: 1,
        outstandingTotal: '50000',
        recoveries: [
          {
            id: 10,
            sourceAdjustmentId: 99,
            originalAmount: '80000',
            allocatedAmount: '30000',
            outstanding: '50000',
            status: 'OPEN',
            createdAt: '2026-08-01T00:00:00Z',
            closedAt: null,
          },
        ],
        allocations: [
          { id: 1, recoveryId: 10, settlementId: 55, amount: '30000', createdAt: '2026-08-05T00:00:00Z' },
        ],
      },
    });

    const result = await recoveryApi.bySeller(1);

    expect(api.get).toHaveBeenCalledWith('/admin/recoveries', { params: { sellerId: 1 } });
    expect(result.outstandingTotal).toBe('50000');
    expect(result.allocations[0].settlementId).toBe(55);
  });

  it('CLOSED 채권만 있으면 잔액은 0 이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        sellerId: 2,
        outstandingTotal: '0',
        recoveries: [
          {
            id: 11,
            sourceAdjustmentId: null,
            originalAmount: '10000',
            allocatedAmount: '10000',
            outstanding: '0',
            status: 'CLOSED',
            createdAt: '2026-07-01T00:00:00Z',
            closedAt: '2026-07-20T00:00:00Z',
          },
        ],
        allocations: [],
      },
    });

    const result = await recoveryApi.bySeller(2);

    expect(result.outstandingTotal).toBe('0');
    expect(result.recoveries[0].status).toBe('CLOSED');
  });
});
