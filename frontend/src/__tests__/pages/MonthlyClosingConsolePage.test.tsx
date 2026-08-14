import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import MonthlyClosingConsolePage from '@/pages/settlement/MonthlyClosingConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { monthlyClosingApi } from '@/api/monthlyClosing';

vi.mock('@/api/monthlyClosing', () => ({
  monthlyClosingApi: { get: vi.fn(), run: vi.fn() },
}));

const mocked = vi.mocked(monthlyClosingApi);

const runOf = (over: Partial<Record<string, unknown>> = {}) => ({
  periodYm: '2026-07', status: 'COMPLETED' as const, triggeredBy: 'admin',
  startedAt: '2026-08-01T00:00:00Z', finishedAt: '2026-08-01T00:01:00Z',
  sellerCount: 2, settlementCount: 10, unmappedCount: 0, pendingCount: 0,
  totalGross: '1000000', totalRefunded: '50000', totalCommission: '35000',
  totalHoldback: '10000', totalNet: '905000', failureReason: null,
  ...over,
});

const closingOf = (over: Partial<Record<string, unknown>> = {}) => ({
  run: runOf(),
  sellers: [
    {
      sellerId: 1, settlementCount: 6, grossAmount: '600000', refundedAmount: '30000',
      commissionAmount: '21000', holdbackAmount: '6000', netAmount: '543000',
    },
    {
      sellerId: 2, settlementCount: 4, grossAmount: '400000', refundedAmount: '20000',
      commissionAmount: '14000', holdbackAmount: '4000', netAmount: '362000',
    },
  ],
  ...over,
} as Awaited<ReturnType<typeof monthlyClosingApi.get>>);

const renderPage = () => render(<ToastProvider><MonthlyClosingConsolePage /></ToastProvider>);

/**
 * 실행 버튼의 라벨은 조회 결과에 달려 있다 — 기존 실행 이력이 렌더된 뒤에야 '마감 재실행'이 되고,
 * 그전에는 '마감 실행'이다. `get` 이 호출됐는지만 기다리면 응답이 반영되기 전에 통과해 라벨을
 * 놓치므로(CI 에서 실제로 산발 실패했다), 버튼 자체가 나타날 때까지 기다린다.
 */
const clickRerun = async () =>
  fireEvent.click(await screen.findByRole('button', { name: '마감 재실행' }));

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue(closingOf());
});

afterEach(() => vi.restoreAllMocks());

describe('MonthlyClosingConsolePage — 조회', () => {
  it('진입하면 지난달을 조회한다 — 이번 달은 아직 마감 대상이 아니다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.get).toHaveBeenCalledTimes(1));
    const d = new Date();
    d.setMonth(d.getMonth() - 1);
    expect(mocked.get).toHaveBeenCalledWith(d.toISOString().slice(0, 7));
  });

  it('마감 이력이 없으면(404) 오류가 아니라 안내로 보여 준다', async () => {
    mocked.get.mockRejectedValue({ isAxiosError: true, response: { status: 404 } });
    renderPage();

    await waitFor(() => expect(screen.getByText(/마감 이력이 없습니다/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '마감 실행' })).toBeInTheDocument();
  });

  it('셀러 마트와 총액을 함께 보여 준다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('셀러 마트 2행')).toBeInTheDocument());
    expect(screen.getByText('905,000원')).toBeInTheDocument();
  });
});

describe('MonthlyClosingConsolePage — 완전성 신호', () => {
  it('매핑 누락·미확정이 있으면 경고하고 재실행을 권한다', async () => {
    mocked.get.mockResolvedValue(closingOf({ run: runOf({ unmappedCount: 3, pendingCount: 2 }) }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/집계에서 빠진 정산이 있습니다/)).toBeInTheDocument());
  });

  it('둘 다 0 이면 경고하지 않는다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('셀러 마트 2행')).toBeInTheDocument());
    expect(screen.queryByText(/집계에서 빠진 정산이 있습니다/)).not.toBeInTheDocument();
  });

  it('run 총액과 셀러 마트 합계가 어긋나면 부분 적재를 경고한다', async () => {
    mocked.get.mockResolvedValue(closingOf({ run: runOf({ totalNet: '999999' }) }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/부분 적재됐을 수 있습니다/)).toBeInTheDocument());
  });
});

describe('MonthlyClosingConsolePage — 실행', () => {
  it('확인을 취소하면 실행하지 않는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    await clickRerun();

    expect(mocked.run).not.toHaveBeenCalled();
  });

  it('이미 완료된 기간의 재실행 확인창은 마트 교체를 알린다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    await clickRerun();

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('통째 교체'));
  });

  it('실행하면 결과를 알리고 다시 조회한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocked.run.mockResolvedValue(runOf());
    renderPage();
    await waitFor(() => expect(mocked.get).toHaveBeenCalledTimes(1));

    await clickRerun();

    await waitFor(() => expect(mocked.run).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mocked.get).toHaveBeenCalledTimes(2));
  });

  it('409(원장 마감된 기간)는 장애가 아니라 사유를 짚어 안내한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocked.run.mockRejectedValue({ isAxiosError: true, response: { status: 409 } });
    renderPage();

    await clickRerun();

    await waitFor(() =>
      expect(screen.getByText(/원장이 마감된 기간이라 재실행할 수 없습니다/)).toBeInTheDocument());
  });

  it('FAILED 로 끝나면 실패 사유를 화면에 남긴다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocked.run.mockResolvedValue(runOf({ status: 'FAILED', failureReason: '원천 데이터 결손' }));
    mocked.get.mockResolvedValue(closingOf({
      run: runOf({ status: 'FAILED', failureReason: '원천 데이터 결손' }),
    }));
    renderPage();

    await clickRerun();

    await waitFor(() => expect(screen.getByText(/실패 사유: 원천 데이터 결손/)).toBeInTheDocument());
  });
});
