import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SettlementAdmin from '@/pages/SettlementAdmin';
import { settlementApi } from '@/api/settlement';
import { openPrintWindow, putPrintHandoff } from '@/lib/printHandoff';

vi.mock('@/api/settlement', () => ({
  settlementApi: { search: vi.fn(), searchByPost: vi.fn(), getSettlement: vi.fn() },
}));

vi.mock('@/lib/printHandoff', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/printHandoff')>();
  return { ...actual, openPrintWindow: vi.fn(), putPrintHandoff: vi.fn() };
});

const mocked = vi.mocked(settlementApi);
const mockedOpen = vi.mocked(openPrintWindow);
const mockedPut = vi.mocked(putPrintHandoff);

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

const detail = (over: Record<string, unknown> = {}) =>
  ({
    settlementId: 55,
    orderId: 100,
    paymentAmount: 20000,
    commissionRate: 0.035,
    commissionAmount: 700,
    netAmount: 19300,
    status: 'DONE',
    settlementDate: '2026-08-01',
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.search.mockResolvedValue(searchResponse());
  mocked.getSettlement.mockResolvedValue(detail());
  mockedOpen.mockReturnValue({} as Window);
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

const renderAndWait = async () => {
  render(<SettlementAdmin />);
  await screen.findByText('티셔츠');
};

describe('SettlementAdmin — 조회', () => {
  it('진입하면 기본 필터로 정산을 검색한다', async () => {
    await renderAndWait();

    expect(screen.getByText('정산 관리 대시보드')).toBeInTheDocument();
    expect(mocked.search).toHaveBeenCalled();
  });

  it('조회 실패는 사유를 보여 주고 닫을 수 있다', async () => {
    mocked.search.mockRejectedValue({ response: { data: { message: '검색 실패' } } });
    render(<SettlementAdmin />);

    expect(await screen.findByText('검색 실패')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '✕' }));
    expect(screen.queryByText('검색 실패')).not.toBeInTheDocument();
  });

  it('상태 필터를 바꾸면 첫 페이지부터 다시 검색한다', async () => {
    await renderAndWait();
    const before = mocked.search.mock.calls.length;

    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: 'DONE' } });

    await waitFor(() => expect(mocked.search.mock.calls.length).toBeGreaterThan(before));
    const lastArg = mocked.search.mock.calls.at(-1)?.[0] as { status?: string; page?: number };
    expect(lastArg.status).toBe('DONE');
    expect(lastArg.page).toBe(0);
  });
});

describe('SettlementAdmin — 상세·인쇄', () => {
  it('상세보기는 그 정산의 상세를 읽어 모달로 보여 준다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '상세보기' }));

    await waitFor(() => expect(mocked.getSettlement).toHaveBeenCalledWith(55));
  });

  it('상세 조회 실패는 사유를 보여 준다', async () => {
    mocked.getSettlement.mockRejectedValue(new Error('down'));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '상세보기' }));

    expect(await screen.findByText('상세 정보를 불러오는데 실패했습니다.')).toBeInTheDocument();
  });

  it('인쇄는 표시값만 핸드오프로 넘기고 새 창을 연다 (금액은 인쇄 창이 재조회)', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '정산서 인쇄' }));

    expect(mockedPut).toHaveBeenCalledWith('settlement', 55, {
      ordererName: '홍길동',
      productName: '티셔츠',
    });
    expect(mockedOpen).toHaveBeenCalledWith('/print/settlement/55');
  });

  it('팝업이 차단되면 그 사실을 안내한다', async () => {
    mockedOpen.mockReturnValue(null);
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '정산서 인쇄' }));

    expect(
      await screen.findByText(/팝업이 차단되어 인쇄 창을 열지 못했습니다/),
    ).toBeInTheDocument();
  });
});
