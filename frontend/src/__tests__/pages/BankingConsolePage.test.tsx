import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import BankingConsolePage from '@/pages/BankingConsolePage';
import { timeDepositApi, savingsApi, pensionApi,
  type TimeDeposit, type InstallmentSavings, type RetirementPension } from '@/api/banking';

vi.mock('@/api/banking', async (importOriginal) => {
  // PENSION_RULES·BENEFIT_RULES 는 실제 값을 쓴다 — 화면이 그 규칙을 제대로 읽는지 함께 본다.
  const actual = await importOriginal<typeof import('@/api/banking')>();
  return {
    ...actual,
    timeDepositApi: { listMine: vi.fn(), open: vi.fn(), closeOnMaturity: vi.fn(), closeEarly: vi.fn() },
    savingsApi: { listMine: vi.fn(), open: vi.fn(), pay: vi.fn(), closeOnMaturity: vi.fn(), closeEarly: vi.fn() },
    pensionApi: {
      listMine: vi.fn(), open: vi.fn(), contribute: vi.fn(), settleInterest: vi.fn(),
      startBenefit: vi.fn(), payBenefit: vi.fn(), withdrawMidway: vi.fn(),
      changeInvestmentInstruction: vi.fn(),
    },
  };
});

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

let role = 'USER';
vi.mock('@/contexts/useAuth', () => ({
  useAuth: () => ({ user: { email: 'a@b.c', role }, userId: 1, loading: false, refresh: vi.fn() }),
}));

const mockedDeposit = vi.mocked(timeDepositApi);
const mockedSavings = vi.mocked(savingsApi);
const mockedPension = vi.mocked(pensionApi);

const deposit = (over: Partial<TimeDeposit> = {}): TimeDeposit => ({
  id: 1, depositorId: '1', productName: '정기예금 12M', principal: 10_000_000,
  annualRate: 3.5, earlyTerminationRate: 0.5, compounding: 'SIMPLE', termMonths: 12,
  openedOn: '2026-01-01', maturityDate: '2027-01-01', status: 'ACTIVE',
  closedOn: null, settledInterest: null, payoutAmount: null, ...over,
});

const savings = (over: Partial<InstallmentSavings> = {}): InstallmentSavings => ({
  id: 2, depositorId: '1', productName: '자유적금', savingsType: 'FLEXIBLE',
  monthlyAmount: 300_000, paymentLimit: null, annualRate: 4, earlyTerminationRate: 1,
  termMonths: 24, openedOn: '2026-01-01', maturityDate: '2028-01-01', status: 'ACTIVE',
  closedOn: null, totalPaidAmount: 900_000, settledInterest: null, payoutAmount: null,
  installments: [
    { round: 1, amount: 300_000, paidOn: '2026-01-01' },
    { round: 2, amount: 300_000, paidOn: '2026-02-01' },
    { round: 3, amount: 300_000, paidOn: '2026-03-01' },
  ], ...over,
});

const pension = (over: Partial<RetirementPension> = {}): RetirementPension => ({
  id: 3, subscriberId: '1', scheme: 'DC', employerName: '가나상사', birthDate: '1970-01-01',
  annualRate: 3, productName: null, productRate: null, status: 'ACCUMULATING',
  openedOn: '2026-01-01', lastInterestSettledOn: null, benefitStartedOn: null,
  benefitType: null, accumulatedAmount: 5_000_000, nextSeq: 1, transactions: [], ...over,
});

const goTo = (label: string) => fireEvent.click(screen.getByRole('tab', { name: label }));

beforeEach(() => {
  vi.clearAllMocks();
  role = 'USER';
  mockedDeposit.listMine.mockResolvedValue([]);
  mockedSavings.listMine.mockResolvedValue([]);
  mockedPension.listMine.mockResolvedValue([]);
  vi.stubGlobal('confirm', vi.fn(() => true));
});
afterEach(() => vi.unstubAllGlobals());

/**
 * 이 화면이 지키는 것.
 *
 * <p>① <b>중도해지 이율을 늘 함께 보여 준다.</b> 만기 이율과 다른데 해지 버튼 옆에 그 숫자가
 * 없으면 "얼마 손해인지 모르고 누르는" 화면이 된다.
 * <p>② <b>제도 규칙을 화면이 미리 막는다.</b> DB 형은 중도인출이 제도적으로 없고 납입 주체도
 * 제도마다 다르다 — 모르면 운영자는 400 을 받고서야 규칙을 알게 된다.
 * <p>③ <b>운영자 전용 조작은 권한이 없으면 그리지 않는다.</b> 운용수익 인식·수급 지급은 서버가
 * ADMIN·MANAGER 로 막는데, 가입자에게 열면 임의 증액이 되기 때문이다.
 */
describe('BankingConsolePage — 정기예금', () => {
  it('중도해지 이율을 목록에 함께 보여 준다', async () => {
    mockedDeposit.listMine.mockResolvedValue([deposit()]);
    render(<BankingConsolePage />);

    await waitFor(() => expect(screen.getByTestId('deposit-1')).toBeInTheDocument());
    expect(screen.getByTestId('early-rate-1')).toHaveTextContent('0.5%');
  });

  it('중도해지 확인 문구에 두 이율을 함께 적는다', async () => {
    // 인자 타입을 적어 둔다 — `vi.fn(() => false)` 는 인자 없는 함수로 추론돼
    // mock.calls[0][0] 접근이 타입 오류가 된다(typecheck:tests 가 잡는다).
    const confirmSpy = vi.fn((_message?: string) => false);
    vi.stubGlobal('confirm', confirmSpy);
    mockedDeposit.listMine.mockResolvedValue([deposit()]);
    render(<BankingConsolePage />);
    await waitFor(() => expect(screen.getByTestId('deposit-1')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '중도 해지' }));

    expect(confirmSpy.mock.calls[0][0]).toContain('3.5%');
    expect(confirmSpy.mock.calls[0][0]).toContain('0.5%');
    expect(mockedDeposit.closeEarly).not.toHaveBeenCalled();
  });

  it('해지된 예금에는 조작 버튼이 없다', async () => {
    mockedDeposit.listMine.mockResolvedValue([deposit({ status: 'CLOSED', closedOn: '2026-06-01' })]);
    render(<BankingConsolePage />);

    await waitFor(() => expect(screen.getByTestId('deposit-1')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '중도 해지' })).not.toBeInTheDocument();
  });
});

describe('BankingConsolePage — 적금', () => {
  it('다음 납입 회차를 이미 낸 회차에서 이어서 계산한다', async () => {
    mockedSavings.listMine.mockResolvedValue([savings()]);
    mockedSavings.pay.mockResolvedValue(savings());
    render(<BankingConsolePage />);
    goTo('적금');
    await waitFor(() => expect(screen.getByTestId('savings-2')).toBeInTheDocument());

    // 3회차까지 냈으므로 다음은 4회차다. 1회차로 보내면 서버가 중복으로 막는다.
    expect(screen.getByText('4회차 납입액')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '납입' }));

    await waitFor(() => expect(mockedSavings.pay).toHaveBeenCalledWith(2, 4, 300_000));
  });

  it('납입 회차 진행도를 보여 준다', async () => {
    mockedSavings.listMine.mockResolvedValue([savings()]);
    render(<BankingConsolePage />);
    goTo('적금');

    await waitFor(() => expect(screen.getByTestId('rounds-2')).toHaveTextContent('3/24'));
    expect(screen.getByTestId('paid-2')).toHaveTextContent('900,000');
  });
});

describe('BankingConsolePage — 퇴직연금 제도 규칙', () => {
  it('DB 형에는 중도인출 버튼을 그리지 않는다 — 제도상 없다', async () => {
    mockedPension.listMine.mockResolvedValue([pension({ scheme: 'DB' })]);
    render(<BankingConsolePage />);
    goTo('퇴직연금');

    await waitFor(() => expect(screen.getByTestId('pension-3')).toBeInTheDocument());
    expect(screen.queryByTestId('withdraw-3')).not.toBeInTheDocument();
  });

  it('DC·IRP 형에는 중도인출 버튼이 있다', async () => {
    mockedPension.listMine.mockResolvedValue([pension({ scheme: 'IRP', employerName: null })]);
    render(<BankingConsolePage />);
    goTo('퇴직연금');

    await waitFor(() => expect(screen.getByTestId('withdraw-3')).toBeInTheDocument());
  });

  it('납입 주체는 제도가 허용하는 것만 고를 수 있다', async () => {
    mockedPension.listMine.mockResolvedValue([pension({ scheme: 'DB' })]);
    render(<BankingConsolePage />);
    goTo('퇴직연금');
    await waitFor(() => expect(screen.getByTestId('pension-3')).toBeInTheDocument());

    // DB 는 회사 납입만이다 — 가입자를 열어 두면 400 왕복이 된다.
    const select = screen.getByLabelText('연금 3 납입 주체') as HTMLSelectElement;
    expect([...select.options].map((o) => o.value)).toEqual(['EMPLOYER']);
  });

  it('IRP 가입 폼에는 사업장명 칸이 없다', async () => {
    render(<BankingConsolePage />);
    goTo('퇴직연금');
    await waitFor(() => expect(screen.getByTestId('pension-open')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('제도'), { target: { value: 'IRP' } });

    expect(screen.queryByLabelText('사업장명')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheme-note')).toHaveTextContent('중도인출 가능');
  });

  it('DB 를 고르면 사업장명이 필수이고 중도인출 불가라고 알린다', async () => {
    render(<BankingConsolePage />);
    goTo('퇴직연금');
    await waitFor(() => expect(screen.getByTestId('pension-open')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('제도'), { target: { value: 'DB' } });

    expect(screen.getByLabelText('사업장명')).toBeInTheDocument();
    expect(screen.getByTestId('scheme-note')).toHaveTextContent('중도인출 불가');
    // 사업장명이 비어 있으면 가입 불가.
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1970-01-01' } });
    fireEvent.change(screen.getByLabelText('적용 이율(%)'), { target: { value: '3' } });
    expect(screen.getByRole('button', { name: '가입' })).toBeDisabled();
  });
});

describe('BankingConsolePage — 운영자 전용 조작', () => {
  it('USER 에게는 운용수익 인식·수급 지급을 그리지 않는다', async () => {
    mockedPension.listMine.mockResolvedValue([pension()]);
    render(<BankingConsolePage />);
    goTo('퇴직연금');

    await waitFor(() => expect(screen.getByTestId('pension-3')).toBeInTheDocument());
    expect(screen.queryByTestId('ops-3')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '운용수익 인식' })).not.toBeInTheDocument();
  });

  it('ADMIN 에게는 보인다', async () => {
    role = 'ADMIN';
    mockedPension.listMine.mockResolvedValue([pension()]);
    render(<BankingConsolePage />);
    goTo('퇴직연금');

    await waitFor(() => expect(screen.getByTestId('ops-3')).toBeInTheDocument());
  });

  it('수급 지급은 수급 개시 후에만 보인다', async () => {
    role = 'MANAGER';
    mockedPension.listMine.mockResolvedValue([pension()]);   // ACCUMULATING
    render(<BankingConsolePage />);
    goTo('퇴직연금');

    await waitFor(() => expect(screen.getByTestId('ops-3')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '운용수익 인식' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수급 지급' })).not.toBeInTheDocument();
  });
});

describe('BankingConsolePage — 조회 실패', () => {
  it('실패를 빈 목록으로 위장하지 않는다', async () => {
    mockedDeposit.listMine.mockRejectedValue(new Error('boom'));
    render(<BankingConsolePage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByTestId('deposit-empty')).not.toBeInTheDocument();
  });
});
