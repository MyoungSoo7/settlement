import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  salesStatsApi,
  type CashflowReport,
  type SalesBreakdown,
  type SalesSummary,
} from '@/api/salesStats';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const totals = {
  transactionCount: 120,
  gmv: 12_000_000,
  refundedAmount: 300_000,
  commissionAmount: 420_000,
  netSettlement: 11_280_000,
  refundRate: 2.5,
};

const mockSummary: SalesSummary = {
  period: { from: '2026-08-01', to: '2026-08-31', days: 31 },
  previousPeriod: { from: '2026-07-01', to: '2026-07-31', days: 31 },
  current: totals,
  previous: { ...totals, gmv: 10_000_000 },
  // 직전 기간에 거래가 없으면 null 이다 — 0 이 아니다.
  growth: { gmv: 0.2, netSettlement: null, transactionCount: -0.1 },
};

const mockBreakdown: SalesBreakdown = {
  dimension: 'PAYMENT_METHOD',
  totalTransactionCount: 120,
  totalGmv: 12_000_000,
  rows: [
    {
      label: 'CARD',
      transactionCount: 100,
      gmv: 10_000_000,
      refundedAmount: 200_000,
      commissionAmount: 350_000,
      netSettlement: 9_450_000,
      sharePercent: 83.33,
    },
  ],
};

const mockCashflow: CashflowReport = {
  period: { from: '2026-08-01', to: '2026-08-07', groupBy: 'day' },
  totals,
  buckets: [
    {
      bucket: '2026-08-01',
      transactionCount: 20,
      gmv: 2_000_000,
      refundedAmount: 0,
      commissionAmount: 70_000,
      netSettlement: 1_930_000,
    },
  ],
};

describe('salesStatsApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('요약은 기간을 params 로 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockSummary });

    const result = await salesStatsApi.summary('2026-08-01', '2026-08-31');

    expect(api.get).toHaveBeenCalledWith('/api/reports/sales-stats/summary', {
      params: { from: '2026-08-01', to: '2026-08-31' },
    });
    expect(result.current.gmv).toBe(12_000_000);
  });

  it('증감률 null 을 0 으로 뭉개지 않는다', async () => {
    // null 은 "직전 기간에 거래가 없었다" 이고, 0 은 "변화 없음" 이다. 클라이언트가 여기서
    // 기본값을 채우면 화면이 정반대의 사실을 말하게 된다.
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockSummary });

    const result = await salesStatsApi.summary('2026-08-01', '2026-08-31');

    expect(result.growth.netSettlement).toBeNull();
    expect(result.growth.gmv).toBe(0.2);
  });

  it('단면 조회는 축과 limit 을 넘기고, limit 기본값은 10 이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockBreakdown });

    await salesStatsApi.breakdown('2026-08-01', '2026-08-31', 'PAYMENT_METHOD');

    expect(api.get).toHaveBeenCalledWith('/api/reports/sales-stats/breakdown', {
      params: { from: '2026-08-01', to: '2026-08-31', dimension: 'PAYMENT_METHOD', limit: 10 },
    });
  });

  it('limit 을 주면 그대로 넘어간다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockBreakdown });

    await salesStatsApi.breakdown('2026-08-01', '2026-08-31', 'SELLER', 5);

    expect(api.get).toHaveBeenCalledWith('/api/reports/sales-stats/breakdown', {
      params: { from: '2026-08-01', to: '2026-08-31', dimension: 'SELLER', limit: 5 },
    });
  });

  it('sharePercent 는 이미 0~100 스케일이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockBreakdown });

    const result = await salesStatsApi.breakdown('2026-08-01', '2026-08-31', 'PAYMENT_METHOD');

    // 여기에 100 을 다시 곱하면 8333% 가 된다.
    expect(result.rows[0].sharePercent).toBeCloseTo(83.33);
  });

  it('추이는 기존 캐시플로우 리포트를 호출한다 (기본 groupBy=day)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockCashflow });

    const result = await salesStatsApi.cashflow('2026-08-01', '2026-08-07');

    // 같은 집계를 두 벌 두면 두 화면이 서로 다른 숫자를 말하게 된다 — 그래서 기존 API 를 쓴다.
    expect(api.get).toHaveBeenCalledWith('/api/reports/cashflow', {
      params: { from: '2026-08-01', to: '2026-08-07', groupBy: 'day' },
    });
    expect(result.buckets).toHaveLength(1);
  });

  it('groupBy 를 바꾸면 그대로 넘어간다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: mockCashflow });

    await salesStatsApi.cashflow('2026-06-01', '2026-08-31', 'month');

    expect(api.get).toHaveBeenCalledWith('/api/reports/cashflow', {
      params: { from: '2026-06-01', to: '2026-08-31', groupBy: 'month' },
    });
  });
});
