import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SettlementDashboardImproved from '@/pages/SettlementDashboardImproved';
import { settlementApi } from '@/api/settlement';

const showToast = vi.fn();

vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/settlement', () => ({
  settlementApi: { search: vi.fn(), searchByPost: vi.fn(), getSettlement: vi.fn() },
}));

const mocked = vi.mocked(settlementApi);

const item = (over: Record<string, unknown> = {}) =>
  ({
    settlementId: 55,
    orderId: 100,
    paymentId: 42,
    ordererName: '홍길동',
    productName: '티셔츠',
    amount: 20000,
    refundedAmount: 0,
    finalAmount: 19300,
    status: 'DONE',
    isRefunded: false,
    settlementDate: '2026-08-01',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

const searchResponse = (over: Record<string, unknown> = {}) =>
  ({
    settlements: [item()],
    totalElements: 1,
    totalPages: 1,
    currentPage: 0,
    pageSize: 20,
    aggregations: {
      totalAmount: 20000,
      totalRefundedAmount: 0,
      totalFinalAmount: 19300,
      statusCounts: { DONE: 1 },
    },
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.search.mockResolvedValue(searchResponse());
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

const renderAndWait = async () => {
  render(<SettlementDashboardImproved />);
  await screen.findByText('티셔츠');
};

describe('SettlementDashboardImproved', () => {
  it('진입 시 한 번 조회한다', async () => {
    await renderAndWait();

    expect(screen.getByText('정산 대시보드')).toBeInTheDocument();
    expect(mocked.search).toHaveBeenCalledTimes(1);
  });

  it('필터를 바꿔도 자동 재조회하지 않는다 — 검색 버튼을 눌러야 돈다', async () => {
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText('주문자명 입력'), '홍길동');

    expect(mocked.search).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
    expect((mocked.search.mock.calls.at(-1)?.[0] as { ordererName?: string }).ordererName).toBe(
      '홍길동',
    );
  });

  it('결과가 없으면 빈 상태 안내를 보여 준다', async () => {
    mocked.search.mockResolvedValue(
      searchResponse({ settlements: [], totalElements: 0, totalPages: 0 }),
    );
    render(<SettlementDashboardImproved />);

    expect(await screen.findByText('정산 데이터가 없습니다')).toBeInTheDocument();
  });

  it('조회 실패는 화면과 토스트 양쪽으로 알린다', async () => {
    mocked.search.mockRejectedValue({ response: { data: { message: '검색 실패' } } });
    render(<SettlementDashboardImproved />);

    expect(await screen.findByText('검색 실패')).toBeInTheDocument();
    expect(showToast).toHaveBeenCalledWith('검색 실패', 'error');
  });

  it('시작일이 종료일보다 늦으면 검색 자체를 잠근다 (누른 뒤 막는 게 아니다)', async () => {
    await renderAndWait();
    const [start, end] = document.querySelectorAll<HTMLInputElement>('input[type="date"]');

    fireEvent.change(start, { target: { value: '2026-08-20' } });
    fireEvent.change(end, { target: { value: '2026-08-01' } });

    // 같은 문구가 날짜 위젯과 폼 하단 두 곳에 나온다 (둘 다 사용자에게 보이는 경고다)
    await waitFor(() =>
      expect(screen.getAllByText('시작일은 종료일보다 이전이어야 합니다.').length).toBeGreaterThanOrEqual(1),
    );
    expect(screen.getByRole('button', { name: '검색' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    expect(mocked.search).toHaveBeenCalledTimes(1); // 초기 1회 그대로
  });

  it('빠른 기간 선택은 시작·종료일을 함께 채운다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '최근 7일' }));

    const [start, end] = document.querySelectorAll<HTMLInputElement>('input[type="date"]');
    expect(start.value).not.toBe('');
    expect(end.value).not.toBe('');
  });

  it('정렬 헤더를 누르면 방향을 토글해 다시 조회한다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByText(/정산일/));
    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
    const last = mocked.search.mock.calls.at(-1)?.[0] as { sortDirection?: string };
    expect(last.sortDirection).toBeDefined();
  });
});
