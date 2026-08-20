import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SalesStatsConsolePage from '@/pages/settlement/SalesStatsConsolePage';
import { salesStatsApi } from '@/api/salesStats';

vi.mock('@/api/salesStats', () => ({
  salesStatsApi: { summary: vi.fn(), breakdown: vi.fn(), cashflow: vi.fn() },
}));

const mocked = vi.mocked(salesStatsApi);

const totals = (gmv: number, count: number) => ({
  transactionCount: count,
  gmv,
  refundedAmount: 0,
  commissionAmount: Math.round(gmv * 0.035),
  netSettlement: gmv - Math.round(gmv * 0.035),
  refundRate: 0,
});

const summaryFixture = {
  period: { from: '2026-01-01', to: '2026-01-31', days: 31 },
  previousPeriod: { from: '2025-12-01', to: '2025-12-31', days: 31 },
  current: totals(2_000_000, 20),
  previous: totals(1_000_000, 10),
  growth: { gmv: 1.0, netSettlement: 1.0, transactionCount: 1.0 },
};

const breakdownFixture = {
  dimension: 'PAYMENT_METHOD' as const,
  totalTransactionCount: 4,
  totalGmv: 10_000,
  rows: [
    {
      label: 'CARD', transactionCount: 3, gmv: 7_500, refundedAmount: 0,
      commissionAmount: 262, netSettlement: 7_238, sharePercent: 75,
    },
    {
      label: 'TRANSFER', transactionCount: 1, gmv: 2_500, refundedAmount: 0,
      commissionAmount: 87, netSettlement: 2_413, sharePercent: 25,
    },
  ],
};

const cashflowFixture = {
  period: { from: '2026-01-01', to: '2026-01-31', groupBy: 'day' },
  totals: totals(10_000, 4),
  buckets: [
    {
      bucket: '2026-01-01', transactionCount: 3, gmv: 7_500,
      refundedAmount: 0, commissionAmount: 262, netSettlement: 7_238,
    },
    {
      bucket: '2026-01-02', transactionCount: 1, gmv: 2_500,
      refundedAmount: 0, commissionAmount: 87, netSettlement: 2_413,
    },
  ],
};

describe('SalesStatsConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.summary.mockResolvedValue(summaryFixture);
    mocked.breakdown.mockResolvedValue(breakdownFixture);
    mocked.cashflow.mockResolvedValue(cashflowFixture);
  });

  it('진입하면 요약·구성·추이를 같은 기간으로 함께 읽는다', async () => {
    render(<SalesStatsConsolePage />);

    await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(1));
    const [from, to] = mocked.summary.mock.calls[0];
    // 셋이 같은 기간이어야 화면 안에서 기간이 어긋난 숫자가 공존하지 않는다.
    expect(mocked.breakdown).toHaveBeenCalledWith(from, to, 'PAYMENT_METHOD', 10);
    expect(mocked.cashflow).toHaveBeenCalledWith(from, to, 'day');
  });

  it('비교 대상 기간을 화면에 밝힌다 — 무엇과 비교한 수치인지 모르면 증감률은 근거가 없다', async () => {
    render(<SalesStatsConsolePage />);

    expect(await screen.findByText(/비교 대상 2025-12-01 ~ 2025-12-31/)).toBeInTheDocument();
  });

  it('증가는 ▲, 감소는 ▼ 로 그린다', async () => {
    mocked.summary.mockResolvedValue({
      ...summaryFixture,
      growth: { gmv: 1.0, netSettlement: -0.25, transactionCount: 0 },
    });
    render(<SalesStatsConsolePage />);

    expect(await screen.findByText('전기 대비 ▲ 100.0%')).toBeInTheDocument();
    expect(screen.getByText('전기 대비 ▼ 25.0%')).toBeInTheDocument();
    expect(screen.getByText('전기 대비 0.0%')).toBeInTheDocument();
  });

  it('직전 기간이 비어 있으면 0% 가 아니라 비교 불가로 알린다', async () => {
    mocked.summary.mockResolvedValue({
      ...summaryFixture,
      previous: totals(0, 0),
      growth: { gmv: null, netSettlement: null, transactionCount: null },
    });
    render(<SalesStatsConsolePage />);

    expect(await screen.findByText(/증감률을 계산할 수 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/전기 대비 ▲/)).not.toBeInTheDocument();
  });

  it('구성비 표에 라벨·거래액·비중·합계를 그린다', async () => {
    render(<SalesStatsConsolePage />);

    expect(await screen.findByText('CARD')).toBeInTheDocument();
    expect(screen.getByText('75.00%')).toBeInTheDocument();
    expect(screen.getByText('25.00%')).toBeInTheDocument();
  });

  it('UNKNOWN 구간은 프로젝션 미도착으로 표시한다 — 금액은 합계에 그대로 남는다', async () => {
    mocked.breakdown.mockResolvedValue({
      ...breakdownFixture,
      rows: [{
        label: 'UNKNOWN', transactionCount: 3, gmv: 30_000, refundedAmount: 0,
        commissionAmount: 0, netSettlement: 30_000, sharePercent: 100,
      }],
    });
    render(<SalesStatsConsolePage />);

    expect(await screen.findByText('프로젝션 미도착')).toBeInTheDocument();
  });

  it('축을 바꾸면 그 축으로 다시 읽는다', async () => {
    const user = userEvent.setup();
    render(<SalesStatsConsolePage />);
    await waitFor(() => expect(mocked.breakdown).toHaveBeenCalledTimes(1));

    await user.selectOptions(screen.getByLabelText('집계 축'), 'SELLER');

    await waitFor(() => expect(mocked.breakdown).toHaveBeenLastCalledWith(
      expect.any(String), expect.any(String), 'SELLER', 10,
    ));
  });

  it('추이 단위를 바꾸면 그 단위로 다시 읽는다', async () => {
    const user = userEvent.setup();
    render(<SalesStatsConsolePage />);
    await waitFor(() => expect(mocked.cashflow).toHaveBeenCalledTimes(1));

    await user.selectOptions(screen.getByLabelText('추이 단위'), 'month');

    await waitFor(() => expect(mocked.cashflow).toHaveBeenLastCalledWith(
      expect.any(String), expect.any(String), 'month',
    ));
  });

  it('거래가 없는 기간은 빈 상태로 안내한다', async () => {
    mocked.breakdown.mockResolvedValue({ ...breakdownFixture, rows: [], totalGmv: 0, totalTransactionCount: 0 });
    mocked.cashflow.mockResolvedValue({ ...cashflowFixture, buckets: [] });
    render(<SalesStatsConsolePage />);

    await waitFor(() => expect(screen.getAllByText('이 기간에 집계된 정산이 없습니다.')).toHaveLength(2));
  });

  it('조회 실패는 서버 문구를 그대로 보여 준다', async () => {
    mocked.summary.mockRejectedValue({
      response: { data: { message: '조회 기간은 최대 366일입니다' } },
    });
    render(<SalesStatsConsolePage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('조회 기간은 최대 366일입니다');
  });
});
