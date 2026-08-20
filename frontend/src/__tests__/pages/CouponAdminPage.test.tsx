import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CouponAdminPage from '@/pages/system/CouponAdminPage';
import { couponAdminApi, type CouponPage, type CouponRow } from '@/api/couponAdmin';
import { saveBlob } from '@/api/auditLog';

vi.mock('@/api/couponAdmin', () => ({
  couponAdminApi: {
    search: vi.fn(),
    lifecycleCounts: vi.fn(),
    enums: vi.fn(),
    usages: vi.fn(),
    activate: vi.fn(),
    deactivate: vi.fn(),
    export: vi.fn(),
  },
}));
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(couponAdminApi);
const mockedSave = vi.mocked(saveBlob);

const coupon = (overrides: Partial<CouponRow> = {}): CouponRow => ({
  id: 1,
  code: 'WELCOME10',
  type: 'PERCENTAGE',
  discountValue: 10,
  minOrderAmount: 0,
  maxDiscountAmount: null,
  maxUses: 100,
  usedCount: 3,
  targetType: 'ALL',
  targetId: null,
  startsAt: null,
  expiresAt: '2027-01-01T00:00:00',
  active: true,
  lifecycle: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00',
  ...overrides,
});

const pageOf = (rows: CouponRow[]): CouponPage => ({
  content: rows,
  page: 0,
  size: 50,
  totalElements: rows.length,
  totalPages: rows.length === 0 ? 0 : 1,
});

describe('CouponAdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.enums.mockResolvedValue({
      lifecycles: ['ACTIVE', 'SCHEDULED', 'EXPIRED', 'EXHAUSTED', 'INACTIVE'],
      types: ['FIXED', 'PERCENTAGE'],
    });
    mocked.lifecycleCounts.mockResolvedValue([]);
    mocked.search.mockResolvedValue(pageOf([]));
  });

  it('한도 0 은 "무제한"으로 적는다 — "발급 불가"로 읽히면 살아 있는 쿠폰을 죽은 것으로 오해한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon({ maxUses: 0, usedCount: 12 })]));
    render(<CouponAdminPage />);

    const table = await screen.findByRole('table');
    expect(within(table).getByText(/12 \/ 무제한/)).toBeInTheDocument();
  });

  it('활성 쿠폰에는 "중단", 비활성에는 "재개"가 보인다 — "삭제"는 없다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('button', { name: '중단' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
  });

  it('비활성 쿠폰에는 재개 버튼이 보인다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon({ active: false, lifecycle: 'INACTIVE' })]));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('button', { name: '재개' })).toBeInTheDocument();
  });

  it('중단하면 코드로 API 를 부르고 목록을 다시 읽는다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.deactivate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await screen.findByRole('button', { name: '중단' });

    const before = mocked.search.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '중단' }));

    await waitFor(() => expect(mocked.deactivate).toHaveBeenCalledWith('WELCOME10'));
    await waitFor(() => expect(mocked.search.mock.calls.length).toBeGreaterThan(before));
  });

  it('중단 안내는 즉시 사용 불가라는 사실을 말한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.deactivate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '중단' }));

    expect(await screen.findByRole('status')).toHaveTextContent('사용할 수 없습니다');
  });

  it('사용 내역을 펼치면 회수된 이력을 사유와 함께 보여 준다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockResolvedValue([
      {
        id: 1, userId: 5, userEmail: 'a@b.c', orderId: 77,
        usedAt: '2026-03-01T10:00:00',
        revokedAt: '2026-03-02T10:00:00', revokeReason: '주문 취소',
      },
    ]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));

    expect(await screen.findByText(/회수됨: 주문 취소/)).toBeInTheDocument();
  });

  it('사용 이력이 없으면 그렇게 말한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));

    expect(await screen.findByText('아직 사용된 적이 없습니다.')).toBeInTheDocument();
  });

  it('상태를 골라도 집계 질의에는 상태를 싣지 않는다', async () => {
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.lifecycleCounts).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('상태'), 'EXPIRED');

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.lifecycle === 'EXPIRED')).toBe(true));
    expect(mocked.lifecycleCounts.mock.calls.every(([q]) => q.lifecycle === undefined)).toBe(true);
  });

  it('상태 드롭다운은 서버 enum 으로 그리되 한국어 라벨을 쓴다', async () => {
    render(<CouponAdminPage />);

    expect(await screen.findByRole('option', { name: '한도 소진' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '중단됨' })).toBeInTheDocument();
  });

  it('결과가 없으면 그렇게 말한다', async () => {
    render(<CouponAdminPage />);

    expect(await screen.findByText('조건에 맞는 쿠폰이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사용자에게 드러낸다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('CSV 가 잘리면 몇 장 중 몇 장인지 말한다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']), fileName: 'coupons.csv', truncated: true, total: 12345,
    });
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('12,345장 중 앞 5,000장');
    expect(mockedSave).toHaveBeenCalled();
  });
});
