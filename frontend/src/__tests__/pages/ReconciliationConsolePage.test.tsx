import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import ReconciliationConsolePage from '@/pages/settlement/ReconciliationConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { reconciliationApi, scanReconciliation } from '@/api/reconciliation';

vi.mock('@/api/reconciliation', () => ({
  reconciliationApi: { run: vi.fn() },
  scanReconciliation: vi.fn(),
}));

const mockedRun = vi.mocked(reconciliationApi.run);
const mockedScan = vi.mocked(scanReconciliation);

const report = (over: Partial<Record<string, unknown>> = {}) => ({
  targetDate: '2026-08-12',
  capturedPayments: '1000000', settlementGross: '1000000',
  refundedAgainstCaptures: '50000', settlementRefunded: '50000',
  captureDiscrepancy: '0', refundDiscrepancy: '0', discrepancy: '0',
  capturedCount: 10, settlementCount: 10, countDiscrepancy: 0,
  matched: true,
  ...over,
} as Awaited<ReturnType<typeof reconciliationApi.run>>);

const renderPage = () => render(<ToastProvider><ReconciliationConsolePage /></ToastProvider>);

beforeEach(() => {
  vi.clearAllMocks();
  mockedRun.mockResolvedValue(report());
  mockedScan.mockResolvedValue([]);
});

describe('ReconciliationConsolePage — 단일 날짜 대사', () => {
  it('진입하면 어제 날짜로 자동 대사한다 — 당일은 처리 중이라 오탐이 난다', async () => {
    renderPage();

    await waitFor(() => expect(mockedRun).toHaveBeenCalledTimes(1));
    const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
    expect(mockedRun).toHaveBeenCalledWith(yesterday);
  });

  it('일치하면 세 축 차이를 0 으로 보여 준다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('일치')).toBeInTheDocument());
    expect(screen.getByText('캡처 금액')).toBeInTheDocument();
    expect(screen.getByText('환불 금액')).toBeInTheDocument();
    expect(screen.getByText('건수 (INV-9)')).toBeInTheDocument();
  });

  it('금액이 어긋나면 불일치 배지와 경보 총량을 보여 준다', async () => {
    mockedRun.mockResolvedValue(report({
      settlementGross: '900000', captureDiscrepancy: '100000', discrepancy: '100000', matched: false,
    }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/불일치 · 경보 총량/)).toBeInTheDocument());
    // 경보 총량 배지와 캡처 축의 '차이' 칸 두 곳에 같은 금액이 나온다
    expect(screen.getAllByText(/100,000원/).length).toBeGreaterThanOrEqual(2);
  });

  it('건수만 어긋나도 불일치로 판정한다 (INV-9)', async () => {
    mockedRun.mockResolvedValue(report({ settlementCount: 9, countDiscrepancy: 1, matched: false }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/불일치/)).toBeInTheDocument());
    expect(screen.getByText('1건')).toBeInTheDocument();
  });

  it('불일치일 때 다음 조사 지점을 안내한다', async () => {
    mockedRun.mockResolvedValue(report({ matched: false }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/프로젝션 유실·조정 누락/)).toBeInTheDocument());
  });

  it('조회에 실패해도 화면이 죽지 않는다', async () => {
    mockedRun.mockRejectedValue(new Error('boom'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/대사를 실행하지 못했습니다|boom/)).toBeInTheDocument());
  });
});

describe('ReconciliationConsolePage — 기간 스캔', () => {
  it('스캔 결과에서 불일치 날짜를 표시하고 상세로 되돌아갈 수 있다', async () => {
    mockedScan.mockResolvedValue([
      { date: '2026-08-10', report: report({ targetDate: '2026-08-10' }), error: null },
      {
        date: '2026-08-11',
        report: report({ targetDate: '2026-08-11', matched: false, captureDiscrepancy: '30000' }),
        error: null,
      },
    ]);
    renderPage();
    await waitFor(() => expect(mockedRun).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '기간 스캔' }));

    await waitFor(() => expect(screen.getByText('2026-08-11')).toBeInTheDocument());
    expect(screen.getByText('불일치')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: '상세' })[1]);
    await waitFor(() => expect(mockedRun).toHaveBeenCalledWith('2026-08-11'));
  });

  it('스캔 중 특정 날짜만 실패하면 그 칸만 조회 실패로 남는다', async () => {
    mockedScan.mockResolvedValue([
      { date: '2026-08-10', report: report({ targetDate: '2026-08-10' }), error: null },
      { date: '2026-08-11', report: null, error: 'timeout' },
    ]);
    renderPage();
    await waitFor(() => expect(mockedRun).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '기간 스캔' }));

    await waitFor(() => expect(screen.getByText('조회 실패')).toBeInTheDocument());
    // 단일 대사 배지 + 성공한 스캔 행 배지 두 개가 '일치' 로 남는다
    expect(screen.getAllByText('일치')).toHaveLength(2);
  });
});
