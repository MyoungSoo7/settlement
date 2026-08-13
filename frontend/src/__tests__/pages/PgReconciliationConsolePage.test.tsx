import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import PgReconciliationConsolePage from '@/pages/settlement/PgReconciliationConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { pgReconciliationApi } from '@/api/pgReconciliation';

vi.mock('@/api/pgReconciliation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/pgReconciliation')>();
  return {
    ...actual,
    pgReconciliationApi: {
      runs: vi.fn(), runDetail: vi.fn(), clawbackPreview: vi.fn(),
      upload: vi.fn(), approve: vi.fn(), reject: vi.fn(), close: vi.fn(),
    },
  };
});

const mocked = vi.mocked(pgReconciliationApi);

const run = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 7, pgProvider: 'TOSS', targetDate: '2026-08-12', fileName: 'toss.csv',
  status: 'COMPLETED', startedAt: '2026-08-13T01:00:00', finishedAt: '2026-08-13T01:00:10',
  totalPgRows: 100, totalInternalRows: 100, matchedCount: 98, discrepancyCount: 2,
  autoCorrectedCount: 1, operatorId: 'admin', closed: false, closedBy: null, closedAt: null,
  ...over,
} as Awaited<ReturnType<typeof pgReconciliationApi.runs>>[number]);

const pendingDiscrepancy = {
  id: 11, runId: 7, type: 'AMOUNT_MISMATCH' as const, paymentId: 501,
  pgTransactionId: 'tx_1', internalAmount: '10000', pgAmount: '9000', difference: '1000',
  status: 'PENDING' as const, resolvedAt: null, resolvedBy: null, note: null,
};

const renderPage = () => render(<ToastProvider><PgReconciliationConsolePage /></ToastProvider>);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.runs.mockResolvedValue([run()]);
  mocked.runDetail.mockResolvedValue({ run: run(), discrepancies: [pendingDiscrepancy] });
  mocked.clawbackPreview.mockResolvedValue({
    runId: 7, clawbackCount: 1, totalClawbackAmount: '1000', noImpactCount: 0,
    lines: [{ discrepancyId: 11, paymentId: 501, type: 'AMOUNT_MISMATCH', clawbackAmount: '1000' }],
  });
});

afterEach(() => vi.restoreAllMocks());

describe('PgReconciliationConsolePage — 목록·상세', () => {
  it('진입하면 최근 실행 목록을 불러온다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalledTimes(1));
    expect(screen.getByText('TOSS')).toBeInTheDocument();
  });

  it('실행을 열면 상세와 회수 미리보기를 함께 부른다 — 승인 판단에 둘 다 필요하다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '열기' }));

    await waitFor(() => expect(mocked.runDetail).toHaveBeenCalledWith(7));
    expect(mocked.clawbackPreview).toHaveBeenCalledWith(7);
    expect(await screen.findByText(/셀러에게서 회수됩니다/)).toBeInTheDocument();
  });

  it('회수 미리보기가 실패해도 상세는 열린다', async () => {
    mocked.clawbackPreview.mockRejectedValue(new Error('boom'));
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '열기' }));

    expect(await screen.findByText('금액 불일치')).toBeInTheDocument();
  });
});

describe('PgReconciliationConsolePage — 승인/거절 안전장치', () => {
  const openDetail = async () => {
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '열기' }));
    await screen.findByText('금액 불일치');
  };

  it('승인 확인창에 회수 금액을 명시한다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('1,000원 이 회수됩니다'));
    expect(mocked.approve).not.toHaveBeenCalled();
  });

  it('승인 확인 후 사유와 함께 승인한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('PG 통보 확인');
    mocked.approve.mockResolvedValue({ ...pendingDiscrepancy, status: 'APPROVED' });
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(mocked.approve).toHaveBeenCalledWith(11, 'PG 통보 확인'));
  });

  it('거절은 사유가 비면 API 를 부르지 않는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('   ');
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '거절' }));

    await waitFor(() => expect(mocked.reject).not.toHaveBeenCalled());
  });
});

describe('PgReconciliationConsolePage — 마감 조건', () => {
  it('미결이 남아 있으면 마감 버튼을 잠근다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '열기' }));

    await screen.findByText('금액 불일치');
    expect(screen.getByRole('button', { name: '대사 마감' })).toBeDisabled();
  });

  it('미결이 0 건이면 마감할 수 있다', async () => {
    mocked.runDetail.mockResolvedValue({
      run: run({ discrepancyCount: 1 }),
      discrepancies: [{ ...pendingDiscrepancy, status: 'APPROVED', resolvedBy: 'admin' }],
    });
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '열기' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '대사 마감' })).toBeEnabled());
  });

  it('이미 마감된 대사는 승인 버튼도 마감 버튼도 열리지 않는다', async () => {
    mocked.runDetail.mockResolvedValue({
      run: run({ closed: true, status: 'CLOSED', closedBy: 'admin', closedAt: '2026-08-13T02:00:00' }),
      discrepancies: [pendingDiscrepancy],
    });
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '열기' }));

    await screen.findByText('금액 불일치');
    expect(screen.getByRole('button', { name: '대사 마감' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument();
  });
});

describe('PgReconciliationConsolePage — 업로드', () => {
  it('파일 없이 누르면 업로드하지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '업로드 + 대사' }));

    expect(mocked.upload).not.toHaveBeenCalled();
  });

  it('파일을 고르면 PG·대상일과 함께 업로드하고 결과를 연다', async () => {
    mocked.upload.mockResolvedValue(run({ id: 8 }));
    mocked.runDetail.mockResolvedValue({ run: run({ id: 8 }), discrepancies: [] });
    renderPage();
    await waitFor(() => expect(mocked.runs).toHaveBeenCalled());

    const file = new File(['pg_transaction_id,amount\n'], 'toss.csv', { type: 'text/csv' });
    fireEvent.change(screen.getByLabelText('정산 CSV'), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: '업로드 + 대사' }));

    await waitFor(() => expect(mocked.upload).toHaveBeenCalledWith('TOSS', expect.any(String), file));
    await waitFor(() => expect(mocked.runDetail).toHaveBeenCalledWith(8));
  });
});
