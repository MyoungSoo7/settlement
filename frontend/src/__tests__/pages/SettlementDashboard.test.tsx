import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettlementDashboard from '@/pages/SettlementDashboard';
import { settlementApi } from '@/api/settlement';

vi.mock('@/api/settlement', () => ({ settlementApi: { search: vi.fn() } }));

const mocked = vi.mocked(settlementApi);

const item = (over: Record<string, unknown> = {}) => ({
  settlementId: 101,
  ordererName: '홍길동',
  productName: '무선 이어폰',
  amount: 120000,
  refundedAmount: 20000,
  finalAmount: 100000,
  status: 'CONFIRMED',
  settlementDate: '2026-08-10',
  ...over,
});

const pageOf = (over: Record<string, unknown> = {}) => ({
  settlements: [item()],
  totalElements: 45,
  totalPages: 3,
  page: 0,
  size: 20,
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  mocked.search.mockResolvedValue(pageOf() as never);
});

/**
 * 정산 대시보드 — 필터·페이지 변경이 <b>곧바로 재조회로 이어지는지</b>가 계약이다.
 *
 * <p>필터만 바뀌고 조회가 안 나가면 화면은 이전 결과를 그대로 들고 있는다. 숫자가 그럴듯해서
 * 운영자는 필터가 먹혔다고 믿는데 실제로는 다른 조건의 결과를 보고 판단하게 된다.
 */
describe('SettlementDashboard — 조회', () => {
  it('진입하면 기본 필터로 한 번 조회하고 결과를 표로 그린다', async () => {
    render(<SettlementDashboard />);

    expect(await screen.findByText('무선 이어폰')).toBeInTheDocument();
    expect(mocked.search).toHaveBeenCalledTimes(1);
    expect(mocked.search).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 20, sortBy: 'createdAt', sortDirection: 'DESC' }));
  });

  it('실패하면 사유를 화면에 남긴다', async () => {
    mocked.search.mockRejectedValue({ isAxiosError: true, response: { data: { message: '검색 실패' } } });
    render(<SettlementDashboard />);

    expect(await screen.findByText(/검색 실패/)).toBeInTheDocument();
  });

  it('상태를 바꾸면 그 상태로 다시 조회한다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    // 필터 바에 '전체' 를 기본값으로 갖는 셀렉트가 둘(정산 상태·정렬)이라 옵션으로 특정한다.
    const statusSelect = screen.getByRole('option', { name: '대기' }).closest('select')!;
    fireEvent.change(statusSelect, { target: { value: 'PENDING' } });

    await waitFor(() =>
      expect(mocked.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'PENDING' })));
  });

  it('상품명을 입력하면 필터에 실어 조회한다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    fireEvent.change(screen.getByPlaceholderText('상품명 입력'), { target: { value: '이어폰' } });

    await waitFor(() =>
      expect(mocked.search).toHaveBeenCalledWith(expect.objectContaining({ productName: '이어폰' })));
  });

  it('검색 버튼은 같은 조건으로 다시 읽는다 — 갱신 수단이다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
  });
});

describe('SettlementDashboard — 표기와 페이지 이동', () => {
  it('금액은 원화로, 상태는 뱃지로 그린다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    // 환불·최종금액이 각각 다른 셀에 나온다 (통화 포맷 경로)
    expect(screen.getByText(/120,000/)).toBeInTheDocument();
    expect(screen.getByText(/100,000/)).toBeInTheDocument();
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
  });

  it('모르는 상태값도 뱃지를 그린다 — 서버에 상태가 추가돼도 화면이 깨지지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf({ settlements: [item({ status: 'ON_HOLD' })] }) as never);
    render(<SettlementDashboard />);

    expect(await screen.findByText('ON_HOLD')).toBeInTheDocument();
  });

  it('첫 페이지에서는 이전으로 갈 수 없다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    expect(screen.getAllByRole('button', { name: '이전' })[0]).toBeDisabled();
  });

  it('다음 페이지로 가면 그 페이지로 다시 조회한다', async () => {
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    fireEvent.click(screen.getAllByRole('button', { name: '다음' })[0]);

    await waitFor(() =>
      expect(mocked.search).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));
  });

  it('마지막 페이지에서는 다음으로 갈 수 없다', async () => {
    mocked.search.mockResolvedValue(pageOf({ totalPages: 1 }) as never);
    render(<SettlementDashboard />);
    await screen.findByText('무선 이어폰');

    expect(screen.getAllByRole('button', { name: '다음' })[0]).toBeDisabled();
  });
});
