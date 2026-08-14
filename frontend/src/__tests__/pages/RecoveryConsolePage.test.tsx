import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import RecoveryConsolePage from '@/pages/settlement/RecoveryConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { recoveryApi } from '@/api/recovery';

vi.mock('@/api/recovery', () => ({ recoveryApi: { bySeller: vi.fn() } }));

const mocked = vi.mocked(recoveryApi);

const summary = (over: Partial<Record<string, unknown>> = {}) => ({
  sellerId: 42,
  outstandingTotal: '30000',
  recoveries: [
    {
      id: 1, sourceAdjustmentId: 900, originalAmount: '50000', allocatedAmount: '20000',
      outstanding: '30000', status: 'OPEN' as const,
      createdAt: '2026-08-01T09:00:00', closedAt: null,
    },
    {
      id: 2, sourceAdjustmentId: 901, originalAmount: '10000', allocatedAmount: '10000',
      outstanding: '0', status: 'CLOSED' as const,
      createdAt: '2026-07-20T09:00:00', closedAt: '2026-07-25T09:00:00',
    },
  ],
  allocations: [
    { id: 11, recoveryId: 1, settlementId: 555, amount: '20000', createdAt: '2026-08-05T09:00:00' },
    { id: 12, recoveryId: 2, settlementId: 556, amount: '10000', createdAt: '2026-07-25T09:00:00' },
  ],
  ...over,
} as Awaited<ReturnType<typeof recoveryApi.bySeller>>);

const renderPage = () => render(<ToastProvider><RecoveryConsolePage /></ToastProvider>);

const search = (id: string) => {
  fireEvent.change(screen.getByPlaceholderText('예: 42'), { target: { value: id } });
  fireEvent.click(screen.getByRole('button', { name: '조회' }));
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.bySeller.mockResolvedValue(summary());
});

describe('RecoveryConsolePage', () => {
  it('진입만으로는 조회하지 않는다 — 셀러를 지정해야 하는 화면이다', () => {
    renderPage();
    expect(mocked.bySeller).not.toHaveBeenCalled();
  });

  it('셀러 ID 가 비면 API 를 부르지 않는다', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.bySeller).not.toHaveBeenCalled());
  });

  it('미상계 잔액 합계를 앞세워 보여 준다', async () => {
    renderPage();
    search('42');

    await waitFor(() => expect(mocked.bySeller).toHaveBeenCalledWith(42));
    expect(await screen.findByText(/미상계 잔액 \(OPEN 1건\)/)).toBeInTheDocument();
    // 합계 배지와 채권 행의 잔액 칸 두 곳에 같은 금액이 나온다
    expect(screen.getAllByText('30,000원')).toHaveLength(2);
  });

  it('채권별 상계 이력을 근거로 함께 펼친다', async () => {
    renderPage();
    search('42');

    await waitFor(() => expect(screen.getByText(/정산 #555 에서 20,000원/)).toBeInTheDocument());
    expect(screen.getByText(/정산 #556 에서 10,000원/)).toBeInTheDocument();
  });

  it('채권이 없으면 빈 상태를 명시한다', async () => {
    mocked.bySeller.mockResolvedValue(summary({ outstandingTotal: '0', recoveries: [], allocations: [] }));
    renderPage();
    search('42');

    await waitFor(() => expect(screen.getByText(/회수 채권이 없습니다/)).toBeInTheDocument());
  });

  it('대응 채권이 없는 상계 이력은 데이터 이상으로 드러낸다', async () => {
    mocked.bySeller.mockResolvedValue(summary({
      allocations: [{ id: 99, recoveryId: 777, settlementId: 1, amount: '5000', createdAt: null }],
    }));
    renderPage();
    search('42');

    await waitFor(() => expect(screen.getByText(/데이터 정합을 확인하세요/)).toBeInTheDocument());
  });

  it('조회 실패는 화면에 남기고 목록을 비운다', async () => {
    mocked.bySeller.mockRejectedValue(new Error('boom'));
    renderPage();
    search('42');

    await waitFor(() => expect(screen.getByText(/회수 채권을 불러오지 못했습니다|boom/)).toBeInTheDocument());
  });
});
