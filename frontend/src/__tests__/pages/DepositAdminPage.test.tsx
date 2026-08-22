import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DepositAdminPage from '@/pages/DepositAdminPage';
import { depositApi, depositAdminApi, type DepositShortfall } from '@/api/deposit';

vi.mock('@/api/deposit', () => ({
  depositApi: { myAccount: vi.fn(), accountOf: vi.fn() },
  depositAdminApi: {
    credit: vi.fn(),
    debit: vi.fn(),
    placeHold: vi.fn(),
    applyOffset: vi.fn(),
    openShortfalls: vi.fn(),
    resolveShortfall: vi.fn(),
    writeOffShortfall: vi.fn(),
  },
}));

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

const mocked = vi.mocked(depositApi);
const admin = vi.mocked(depositAdminApi);

const account = (available = 100000, locked = 20000) => ({
  id: 1,
  sellerId: 7,
  available,
  locked,
  total: available + locked,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
});

const shortfall = (overrides: Partial<DepositShortfall> = {}): DepositShortfall => ({
  id: 31,
  sellerId: 7,
  holderType: 'CARD_AUTHORIZATION',
  holderReference: 'AUTH-9001',
  requestedAmount: 50000,
  appliedAmount: 30000,
  shortfallAmount: 20000,
  status: 'OPEN',
  sourceHoldId: null,
  occurredAt: '2026-08-20T02:00:00Z',
  ...overrides,
});

/** 셀러를 조회해 조작 폼을 연다. 조회 전에는 폼 자체가 없다. */
const lookupSeller = async (user: ReturnType<typeof userEvent.setup>, id = '7') => {
  await user.type(await screen.findByLabelText('셀러 번호'), id);
  await user.click(screen.getByRole('button', { name: '잔고 조회' }));
  await screen.findByTestId('deposit-ops');
};

const fillEntry = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.type(screen.getByLabelText('금액'), '50000');
  await user.type(screen.getByLabelText('참조 유형'), 'MANUAL_ADJUSTMENT');
  await user.type(screen.getByLabelText('참조 번호'), 'TICKET-1234');
};

beforeEach(() => {
  vi.clearAllMocks();
  showToast.mockClear();
  mocked.accountOf.mockResolvedValue(account());
  admin.openShortfalls.mockResolvedValue([]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DepositAdminPage — 잔고를 모르고 조작하지 않는다', () => {
  it('조회하기 전에는 조작 폼 자체가 없다 — 눈 감고 돈을 옮기지 않게', async () => {
    render(<DepositAdminPage />);

    await screen.findByLabelText('셀러 번호');
    expect(screen.queryByTestId('deposit-ops')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '입금' })).not.toBeInTheDocument();
  });

  it('셀러 번호가 비었거나 0 이하·정수가 아니면 조회 버튼이 잠긴다', async () => {
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    const button = await screen.findByRole('button', { name: '잔고 조회' });
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText('셀러 번호'), '0');
    expect(button).toBeDisabled();

    await user.clear(screen.getByLabelText('셀러 번호'));
    await user.type(screen.getByLabelText('셀러 번호'), '7');
    expect(button).toBeEnabled();
  });

  it('사용 가능·묶인 금액·합계를 나눠 보여 준다 — 합계만 보면 "왜 못 쓰지"에 답할 수 없다', async () => {
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await lookupSeller(user);

    expect(screen.getByTestId('bal-available')).toHaveTextContent('100,000');
    expect(screen.getByTestId('bal-locked')).toHaveTextContent('20,000');
    expect(screen.getByTestId('bal-total')).toHaveTextContent('120,000');
  });

  it('셀러 번호를 고치면 조회 결과와 조작 폼을 버린다 — "A 를 보고 B 를 조작"을 만들지 않는다', async () => {
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.type(screen.getByLabelText('셀러 번호'), '9');

    await waitFor(() => expect(screen.queryByTestId('deposit-ops')).not.toBeInTheDocument());
  });

  it('계좌가 없으면 "없다"고 말하고 무엇이 실패할지 알려 준다 — 잔고 0 과 구분한다', async () => {
    mocked.accountOf.mockResolvedValue(null);
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await lookupSeller(user);

    expect(screen.getByTestId('deposit-no-account')).toHaveTextContent('아직 예치 계좌가 없습니다');
    expect(screen.queryByTestId('deposit-balance')).not.toBeInTheDocument();
  });

  it('조회 실패는 드러낸다', async () => {
    mocked.accountOf.mockRejectedValue({ response: { data: { message: '권한이 없습니다' } } });
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.type(await screen.findByLabelText('셀러 번호'), '7');
    await user.click(screen.getByRole('button', { name: '잔고 조회' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다');
    expect(screen.queryByTestId('deposit-ops')).not.toBeInTheDocument();
  });
});

describe('DepositAdminPage — 입금·출금', () => {
  it('참조 유형·번호가 비면 버튼이 잠긴다 — 멱등 키 없는 조작을 허용하지 않는다', async () => {
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    expect(screen.getByRole('button', { name: '입금' })).toBeDisabled();

    await user.type(screen.getByLabelText('금액'), '50000');
    expect(screen.getByRole('button', { name: '입금' })).toBeDisabled();

    await user.type(screen.getByLabelText('참조 유형'), 'MANUAL_ADJUSTMENT');
    expect(screen.getByRole('button', { name: '입금' })).toBeDisabled();

    await user.type(screen.getByLabelText('참조 번호'), 'TICKET-1234');
    expect(screen.getByRole('button', { name: '입금' })).toBeEnabled();
  });

  it('입금은 운영자가 적은 참조를 그대로 보낸다 — 화면이 키를 지어내면 중복 방어가 사라진다', async () => {
    admin.credit.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await fillEntry(user);

    await user.click(screen.getByRole('button', { name: '입금' }));

    await waitFor(() => expect(admin.credit).toHaveBeenCalledWith(7, {
      amount: 50000, referenceType: 'MANUAL_ADJUSTMENT', referenceId: 'TICKET-1234',
    }));
  });

  it('입금 뒤 입력칸을 비우고 잔고를 다시 읽는다 — 옛 숫자가 남으면 다음 판단이 틀어진다', async () => {
    admin.credit.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    const before = mocked.accountOf.mock.calls.length;
    await fillEntry(user);

    await user.click(screen.getByRole('button', { name: '입금' }));

    await waitFor(() => expect(screen.getByLabelText('참조 번호')).toHaveValue(''));
    expect(mocked.accountOf.mock.calls.length).toBeGreaterThan(before);
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('입금을 접수했습니다'), 'success');
  });

  it('조작은 됐는데 잔고 재조회만 실패하면, 성공은 성공대로 알리고 옛 숫자는 지운다', async () => {
    admin.credit.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await fillEntry(user);
    // 조작은 성공, 뒤이은 갱신만 실패시킨다.
    mocked.accountOf.mockRejectedValueOnce(new Error('boom'));

    await user.click(screen.getByRole('button', { name: '입금' }));

    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('입금을 접수했습니다'), 'success');
    // 옛 잔고를 그대로 두면 조작 전 숫자를 조작 후 값으로 오해한다.
    await waitFor(() => expect(screen.queryByTestId('deposit-balance')).not.toBeInTheDocument());
    expect(screen.getByTestId('deposit-no-account')).toBeInTheDocument();
  });

  it('출금은 확인을 받고, 취소하면 아무 일도 없다 — 되돌리는 경로가 없다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await fillEntry(user);

    await user.click(screen.getByRole('button', { name: '출금' }));

    expect(admin.debit).not.toHaveBeenCalled();
  });

  it('출금 확인 문구에는 금액과 참조가 함께 나온다', async () => {
    const confirmSpy = vi.fn().mockReturnValue(true);
    vi.stubGlobal('confirm', confirmSpy);
    admin.debit.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await fillEntry(user);

    await user.click(screen.getByRole('button', { name: '출금' }));

    expect(confirmSpy.mock.calls[0][0]).toContain('MANUAL_ADJUSTMENT / TICKET-1234');
    await waitFor(() => expect(admin.debit).toHaveBeenCalledWith(7, expect.objectContaining({
      referenceId: 'TICKET-1234',
    })));
  });

  it('조작 실패는 드러내고 성공 토스트를 띄우지 않는다', async () => {
    admin.credit.mockRejectedValue({ response: { data: { message: '중복 요청입니다' } } });
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await fillEntry(user);

    await user.click(screen.getByRole('button', { name: '입금' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('중복 요청입니다');
    expect(showToast).not.toHaveBeenCalled();
  });
});

describe('DepositAdminPage — 선점', () => {
  it('참조·금액이 있어야 걸 수 있다', async () => {
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    expect(screen.getByRole('button', { name: '선점 걸기' })).toBeDisabled();

    await user.type(screen.getByLabelText('선점 참조'), 'AUTH-9001');
    await user.type(screen.getByLabelText('선점 금액'), '30000');

    expect(screen.getByRole('button', { name: '선점 걸기' })).toBeEnabled();
  });

  it('만료를 비우면 아예 싣지 않는다 — 서버 기본값(72시간)을 화면이 덮어쓰지 않게', async () => {
    admin.placeHold.mockResolvedValue({
      id: 5, accountId: 1, holderType: 'CARD_AUTHORIZATION', holderReference: 'AUTH-9001',
      originalAmount: 30000, remainingAmount: 30000, status: 'ACTIVE', expiresAt: null,
    });
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.type(screen.getByLabelText('선점 참조'), 'AUTH-9001');
    await user.type(screen.getByLabelText('선점 금액'), '30000');
    await user.click(screen.getByRole('button', { name: '선점 걸기' }));

    await waitFor(() => expect(admin.placeHold).toHaveBeenCalledWith(7, {
      holderType: 'CARD_AUTHORIZATION',
      holderReference: 'AUTH-9001',
      amount: 30000,
    }));
  });

  it('만료를 적으면 그 값을 실어 보낸다 — 무기한 선점은 만들 수 없다', async () => {
    admin.placeHold.mockResolvedValue({
      id: 7, accountId: 1, holderType: 'CARD_AUTHORIZATION', holderReference: 'AUTH-9002',
      originalAmount: 30000, remainingAmount: 30000, status: 'ACTIVE',
      expiresAt: '2026-09-01T10:00:00Z',
    });
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.type(screen.getByLabelText('선점 참조'), 'AUTH-9002');
    await user.type(screen.getByLabelText('선점 금액'), '30000');
    await user.type(screen.getByLabelText('만료'), '2026-09-01T10:00');
    await user.click(screen.getByRole('button', { name: '선점 걸기' }));

    await waitFor(() => expect(admin.placeHold).toHaveBeenCalledWith(7,
      expect.objectContaining({ expiresAt: '2026-09-01T10:00' })));
  });

  it('선점 주체를 고르면 그대로 실린다', async () => {
    admin.placeHold.mockResolvedValue({
      id: 6, accountId: 1, holderType: 'LOAN_DISBURSEMENT', holderReference: 'LOAN-1',
      originalAmount: 10000, remainingAmount: 10000, status: 'ACTIVE', expiresAt: null,
    });
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.selectOptions(screen.getByLabelText('선점 주체'), 'LOAN_DISBURSEMENT');
    await user.type(screen.getByLabelText('선점 참조'), 'LOAN-1');
    await user.type(screen.getByLabelText('선점 금액'), '10000');
    await user.click(screen.getByRole('button', { name: '선점 걸기' }));

    await waitFor(() => expect(admin.placeHold).toHaveBeenCalledWith(7,
      expect.objectContaining({ holderType: 'LOAN_DISBURSEMENT' })));
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('선점 #6'), 'success');
  });
});

describe('DepositAdminPage — 상계는 모자라도 실패가 아니다', () => {
  it('확인 문구가 "부족분은 자동으로 해소되지 않는다"고 미리 알린다', async () => {
    const confirmSpy = vi.fn().mockReturnValue(false);
    vi.stubGlobal('confirm', confirmSpy);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.type(screen.getByLabelText('상계 참조'), 'AUTH-9001');
    await user.type(screen.getByLabelText('상계 금액'), '50000');
    await user.click(screen.getByRole('button', { name: '상계 걸기' }));

    expect(confirmSpy.mock.calls[0][0]).toContain('자동으로 해소되지 않습니다');
    expect(admin.applyOffset).not.toHaveBeenCalled();
  });

  it('분할 회차를 숫자로 실어 보낸다 — 같은 번호 재전송은 DB 가 막는다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    admin.applyOffset.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.type(screen.getByLabelText('상계 참조'), 'AUTH-9001');
    await user.type(screen.getByLabelText('상계 금액'), '50000');
    await user.clear(screen.getByLabelText('분할 회차'));
    await user.type(screen.getByLabelText('분할 회차'), '2');
    await user.click(screen.getByRole('button', { name: '상계 걸기' }));

    await waitFor(() => expect(admin.applyOffset).toHaveBeenCalledWith(7, {
      holderType: 'CARD_AUTHORIZATION',
      holderReference: 'AUTH-9001',
      offsetAmount: 50000,
      offsetSequence: 2,
    }));
  });

  it('상계 주체도 고른 값이 실린다 — 입출금과 달리 주체+참조가 멱등 키다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    admin.applyOffset.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);

    await user.selectOptions(screen.getByLabelText('상계 주체'), 'INVESTMENT_EXECUTION');
    await user.type(screen.getByLabelText('상계 참조'), 'INV-77');
    await user.type(screen.getByLabelText('상계 금액'), '10000');
    await user.click(screen.getByRole('button', { name: '상계 걸기' }));

    await waitFor(() => expect(admin.applyOffset).toHaveBeenCalledWith(7,
      expect.objectContaining({ holderType: 'INVESTMENT_EXECUTION', holderReference: 'INV-77' })));
  });

  it('상계 뒤 부족분 목록을 다시 읽는다 — 방금 만든 부족분이 화면에 없으면 없는 줄 안다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    admin.applyOffset.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await lookupSeller(user);
    await waitFor(() => expect(admin.openShortfalls).toHaveBeenCalled());
    const before = admin.openShortfalls.mock.calls.length;

    await user.type(await screen.findByLabelText('상계 참조'), 'AUTH-9001');
    await user.type(screen.getByLabelText('상계 금액'), '50000');
    await user.click(screen.getByRole('button', { name: '상계 걸기' }));

    await waitFor(() =>
      expect(admin.openShortfalls.mock.calls.length).toBeGreaterThan(before));
  });
});

describe('DepositAdminPage — 부족분 큐', () => {
  it('0건과 조회 실패를 구분한다 — 빈 표는 실패를 "깨끗함"으로 위장시킨다', async () => {
    // 사유를 못 읽는 오류 → 화면용 기본 문구로 떨어진다(errorDetail 규약).
    admin.openShortfalls.mockRejectedValue({});
    render(<DepositAdminPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('부족분 목록을 불러오지 못했습니다.');
    expect(screen.queryByTestId('shortfall-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('shortfall-table')).not.toBeInTheDocument();
  });

  it('정말 0건이면 그렇게 말한다', async () => {
    render(<DepositAdminPage />);

    expect(await screen.findByTestId('shortfall-empty')).toHaveTextContent('미해소 부족분이 없습니다.');
  });

  it('요청·적용·부족을 셋 다 보여 준다 — 부족분만으로는 어느 건이 급한지 모른다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    render(<DepositAdminPage />);

    const row = await screen.findByTestId('shortfall-row-31');
    expect(within(row).getByText(/50,000/)).toBeInTheDocument();
    expect(within(row).getByText(/30,000/)).toBeInTheDocument();
    expect(within(row).getByText(/20,000/)).toBeInTheDocument();
    expect(within(row).getByText(/카드 승인 · AUTH-9001/)).toBeInTheDocument();
  });

  it('해소는 "실제로 차감된다"고 먼저 알리고, 취소하면 부르지 않는다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    const confirmSpy = vi.fn().mockReturnValue(false);
    vi.stubGlobal('confirm', confirmSpy);
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.click(await screen.findByRole('button', { name: '해소' }));

    expect(confirmSpy.mock.calls[0][0]).toContain('실제로 차감됩니다');
    expect(admin.resolveShortfall).not.toHaveBeenCalled();
  });

  it('해소하면 차감액을 알리고 목록을 다시 읽는다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    admin.resolveShortfall.mockResolvedValue(20000);
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await screen.findByTestId('shortfall-row-31');
    const before = admin.openShortfalls.mock.calls.length;

    await user.click(screen.getByRole('button', { name: '해소' }));

    await waitFor(() => expect(admin.resolveShortfall).toHaveBeenCalledWith(31));
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('부족분 #31 해소'), 'success');
    await waitFor(() =>
      expect(admin.openShortfalls.mock.calls.length).toBeGreaterThan(before));
  });

  it('상각은 되돌릴 수 없다는 사실을 확인 문구에 적는다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    admin.writeOffShortfall.mockResolvedValue(undefined);
    const confirmSpy = vi.fn().mockReturnValue(true);
    vi.stubGlobal('confirm', confirmSpy);
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.click(await screen.findByRole('button', { name: '상각' }));

    expect(confirmSpy.mock.calls[0][0]).toContain('되돌리는 경로가 없습니다');
    await waitFor(() => expect(admin.writeOffShortfall).toHaveBeenCalledWith(31));
  });

  it('해소 실패는 그 행 번호와 함께 드러낸다 — 어느 건에서 멈췄는지 알아야 한다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    admin.resolveShortfall.mockRejectedValue({});
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.click(await screen.findByRole('button', { name: '해소' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('부족분 #31 해소에 실패했습니다.');
  });

  it('가용액이 모자라 거절되면 서버가 준 사유를 그대로 보여 준다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    admin.resolveShortfall.mockRejectedValue({
      response: { status: 409, data: { message: '가용 잔고가 부족합니다' } },
    });
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.click(await screen.findByRole('button', { name: '해소' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('가용 잔고가 부족합니다');
    expect(showToast).not.toHaveBeenCalled();
  });

  it('상각 실패도 그 행 번호와 함께 드러낸다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    admin.writeOffShortfall.mockRejectedValue({});
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    const user = userEvent.setup();
    render(<DepositAdminPage />);

    await user.click(await screen.findByRole('button', { name: '상각' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('부족분 #31 상각에 실패했습니다.');
  });

  it('새로고침은 목록만 다시 읽는다', async () => {
    admin.openShortfalls.mockResolvedValue([shortfall()]);
    const user = userEvent.setup();
    render(<DepositAdminPage />);
    await screen.findByTestId('shortfall-row-31');
    const before = admin.openShortfalls.mock.calls.length;

    await user.click(screen.getByRole('button', { name: '새로고침' }));

    await waitFor(() =>
      expect(admin.openShortfalls.mock.calls.length).toBeGreaterThan(before));
  });
});
