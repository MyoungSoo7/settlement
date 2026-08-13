import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import LedgerConsolePage from '@/pages/settlement/LedgerConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { ledgerApi } from '@/api/ledger';

vi.mock('@/api/ledger', () => ({
  ledgerApi: {
    entries: vi.fn(),
    bySettlement: vi.fn(),
    byRefund: vi.fn(),
    period: vi.fn(),
    trialBalance: vi.fn(),
    close: vi.fn(),
  },
}));

const mocked = vi.mocked(ledgerApi);

const entry = (id: number, status: string) => ({
  id, referenceId: 1024, referenceType: 'SETTLEMENT', entryType: 'SETTLEMENT',
  debitAccount: 'PLATFORM_RECEIVABLE', creditAccount: 'SELLER_PAYABLE',
  amount: '100000', status: status as 'POSTED', settlementDate: '2026-08-01',
  postedAt: '2026-08-01T10:00:00', memo: null, createdAt: '2026-08-01T10:00:00',
});

const balanced = {
  periodYm: '2026-08',
  lines: [{ account: 'SELLER_PAYABLE', debit: '0', credit: '100000', net: '-100000' }],
  totalDebit: '100000', totalCredit: '100000', balanced: true,
};

const renderPage = () => render(<ToastProvider><LedgerConsolePage /></ToastProvider>);

const asRole = (role: string) => {
  localStorage.setItem('access_token', 'token');
  localStorage.setItem('user_email', 'x@example.com');
  localStorage.setItem('user_role', role);
};

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  mocked.entries.mockResolvedValue([entry(1, 'POSTED'), entry(2, 'REVERSED')]);
  mocked.trialBalance.mockResolvedValue(balanced);
  mocked.period.mockResolvedValue({
    id: 1, periodYm: '2026-08', status: 'OPEN', closedAt: null, closedBy: null,
    totalDebit: '100000', totalCredit: '100000', createdAt: '2026-08-01T00:00:00',
  });
});

afterEach(() => localStorage.clear());

describe('LedgerConsolePage — 권한 분기', () => {
  it('ADMIN 은 시산표를 보고, 차대 일치를 명시적으로 알려 준다', async () => {
    asRole('ADMIN');
    renderPage();

    await waitFor(() => expect(mocked.trialBalance).toHaveBeenCalledWith(expect.any(String)));
    expect(screen.getByText(/차대 일치/)).toBeInTheDocument();
    // 계정명은 분개 표에도 나오므로, 시산표에만 있는 잔액 칸으로 확인한다
    expect(screen.getByText('-100,000원')).toBeInTheDocument();
  });

  it('MANAGER 에게는 시산표를 호출조차 하지 않는다 — 서버가 ADMIN 으로 막는 표면', async () => {
    asRole('MANAGER');
    renderPage();

    await waitFor(() => expect(mocked.entries).toHaveBeenCalled());
    expect(mocked.trialBalance).not.toHaveBeenCalled();
    expect(mocked.period).not.toHaveBeenCalled();
    expect(screen.getByText(/시산표·기간 마감은 최고 관리자 전용/)).toBeInTheDocument();
  });
});

describe('LedgerConsolePage — 마감 안전장치', () => {
  it('차대 불균형이면 마감 버튼을 잠근다 — 깨진 장부를 봉인하지 않는다', async () => {
    asRole('ADMIN');
    mocked.trialBalance.mockResolvedValue({ ...balanced, totalCredit: '90000', balanced: false });
    renderPage();

    await waitFor(() => expect(screen.getByText(/차대 불균형/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '기간 마감' })).toBeDisabled();
  });

  it('이미 마감된 기간이면 버튼을 잠근다', async () => {
    asRole('ADMIN');
    mocked.period.mockResolvedValue({
      id: 1, periodYm: '2026-08', status: 'CLOSED', closedAt: '2026-09-01T00:00:00',
      closedBy: 'admin', totalDebit: '100000', totalCredit: '100000', createdAt: '2026-08-01T00:00:00',
    });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '기간 마감' })).toBeDisabled());
  });

  it('확인을 취소하면 마감 API 를 부르지 않는다', async () => {
    asRole('ADMIN');
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '기간 마감' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '기간 마감' }));

    expect(mocked.close).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('확인하면 마감하고 상태를 갱신한다', async () => {
    asRole('ADMIN');
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocked.close.mockResolvedValue({
      id: 1, periodYm: '2026-08', status: 'CLOSED', closedAt: '2026-09-01T00:00:00',
      closedBy: 'admin', totalDebit: '100000', totalCredit: '100000', createdAt: '2026-08-01T00:00:00',
    });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '기간 마감' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '기간 마감' }));

    await waitFor(() => expect(mocked.close).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', { name: '기간 마감' })).toBeDisabled());
    confirmSpy.mockRestore();
  });
});

describe('LedgerConsolePage — 분개 조회', () => {
  it('진입하면 기간 분개를 불러오고 건수·합계를 보여 준다', async () => {
    asRole('MANAGER');
    renderPage();

    await waitFor(() => expect(screen.getByText(/2건 ·/)).toBeInTheDocument());
    expect(screen.getByText('POSTED')).toBeInTheDocument();
    expect(screen.getByText('REVERSED')).toBeInTheDocument();
  });

  it('정산 ID 로 단건 추적하면 전용 API 를 부른다', async () => {
    asRole('MANAGER');
    mocked.bySettlement.mockResolvedValue([entry(9, 'POSTED')]);
    renderPage();
    await waitFor(() => expect(mocked.entries).toHaveBeenCalled());

    fireEvent.change(screen.getByPlaceholderText('예: 1024'), { target: { value: '1024' } });
    fireEvent.click(screen.getByRole('button', { name: '추적' }));

    await waitFor(() => expect(mocked.bySettlement).toHaveBeenCalledWith(1024));
    expect(await screen.findByText('정산 #1024')).toBeInTheDocument();
  });

  it('ID 없이 추적을 누르면 API 를 부르지 않는다', async () => {
    asRole('MANAGER');
    renderPage();
    await waitFor(() => expect(mocked.entries).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '추적' }));

    expect(mocked.bySettlement).not.toHaveBeenCalled();
    expect(mocked.byRefund).not.toHaveBeenCalled();
  });
});
