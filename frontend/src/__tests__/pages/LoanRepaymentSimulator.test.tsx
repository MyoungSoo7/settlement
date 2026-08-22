import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LoanPage from '@/pages/LoanPage';
import { repaymentApi, type RepaymentSchedule } from '@/api/loan';

vi.mock('@/api/loan', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/loan')>();
  return {
    ...actual,
    loanApi: {
      request: vi.fn(), myLoans: vi.fn().mockResolvedValue([]),
      corporateCredit: vi.fn(), requestCorporate: vi.fn(),
      repayCorporate: vi.fn(), corporateByStock: vi.fn().mockResolvedValue([]),
    },
    repaymentApi: { simulate: vi.fn() },
  };
});
vi.mock('@/api/financial', () => ({ financialApi: { companies: vi.fn().mockResolvedValue({ content: [] }) } }));
vi.mock('@/api/auth', () => ({
  authApi: { getCurrentUser: () => ({ id: 1, email: 'a@b.c', role: 'ADMIN' }), isAuthenticated: () => true },
}));

const mocked = vi.mocked(repaymentApi);

const schedule = (over: Partial<RepaymentSchedule> = {}): RepaymentSchedule => ({
  principal: 100_000_000, termMonths: 3, annualRatePercent: 5.5,
  method: 'EQUAL_PAYMENT', methodLabel: '원리금균등상환',
  totalPrincipal: 100_000_000, totalInterest: 1_100_000, totalPayment: 101_100_000,
  installments: [
    { installmentNo: 1, principalPortion: 33_100_000, interest: 458_333, payment: 33_558_333, remainingBalance: 66_900_000 },
    { installmentNo: 2, principalPortion: 33_252_000, interest: 306_625, payment: 33_558_625, remainingBalance: 33_648_000 },
    { installmentNo: 3, principalPortion: 33_648_000, interest: 154_220, payment: 33_802_220, remainingBalance: 0 },
  ],
  ...over,
});

const openTab = () => {
  render(<LoanPage />);
  fireEvent.click(screen.getByRole('button', { name: '상환표' }));
};

beforeEach(() => vi.clearAllMocks());

/**
 * 이 구획이 다른 콘솔들과 다른 점: <b>부수효과가 없다</b>.
 *
 * <p>서버가 "대출 생성·영속화와 무관한 순수 미리보기"라고 못박은 표면이라 확인 절차를 두지
 * 않는다 — 확인을 붙이면 "이것도 위험한가" 하는 잘못된 신호가 된다. 대신 화면이 그 사실을
 * 문구로 말한다.
 *
 * <p>두 번째: <b>상환방식 한글명을 화면이 짓지 않는다</b>. 서버가 methodLabel 로 내려준다 —
 * 코드↔라벨 표를 화면이 또 들고 있으면 서버가 방식을 추가할 때 조용히 어긋난다.
 */
describe('LoanPage — 상환표 시뮬레이터', () => {
  it('탭을 열면 저장되지 않는 계산이라고 말한다', () => {
    openTab();

    expect(screen.getByTestId('repayment-simulator')).toHaveTextContent('아무것도 저장되지 않습니다');
  });

  it('확인 절차 없이 바로 계산한다 — 되돌릴 것이 없다', async () => {
    const confirmSpy = vi.fn(() => true);
    vi.stubGlobal('confirm', confirmSpy);
    mocked.simulate.mockResolvedValue(schedule());
    openTab();

    fireEvent.click(screen.getByRole('button', { name: '계산' }));

    await waitFor(() => expect(mocked.simulate).toHaveBeenCalled());
    expect(confirmSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('입력한 값을 그대로 보낸다', async () => {
    mocked.simulate.mockResolvedValue(schedule());
    openTab();

    fireEvent.change(screen.getByLabelText('원금'), { target: { value: '50000000' } });
    fireEvent.change(screen.getByLabelText('기간(개월)'), { target: { value: '24' } });
    fireEvent.change(screen.getByLabelText('연이율(%)'), { target: { value: '4.2' } });
    fireEvent.change(screen.getByLabelText('상환방식'), { target: { value: 'EQUAL_PRINCIPAL' } });
    fireEvent.click(screen.getByRole('button', { name: '계산' }));

    await waitFor(() => expect(mocked.simulate).toHaveBeenCalledWith({
      principal: 50_000_000, termMonths: 24, annualRatePercent: 4.2, method: 'EQUAL_PRINCIPAL',
    }));
  });

  it('상환방식 이름은 서버가 준 것을 쓴다 — 화면이 표를 또 들지 않는다', async () => {
    mocked.simulate.mockResolvedValue(schedule({ methodLabel: '원리금균등상환' }));
    openTab();

    fireEvent.click(screen.getByRole('button', { name: '계산' }));

    expect(await screen.findByTestId('method-label')).toHaveTextContent('원리금균등상환');
  });

  it('회차별 상환표와 합계를 보여 준다', async () => {
    mocked.simulate.mockResolvedValue(schedule());
    openTab();

    fireEvent.click(screen.getByRole('button', { name: '계산' }));

    await waitFor(() => expect(screen.getByTestId('installment-table')).toBeInTheDocument());
    // 방식을 바꿔 보는 이유가 이 숫자다.
    expect(screen.getByTestId('total-interest')).toHaveTextContent('1,100,000');
    expect(screen.getByTestId('total-payment')).toHaveTextContent('101,100,000');
    // 3회차가 모두 그려지고 마지막 잔액이 0 이다.
    expect(screen.getByTestId('installment-table').querySelectorAll('tbody tr')).toHaveLength(3);
  });

  it('기간이 1~600 밖이면 미리 막고 이유를 말한다', () => {
    openTab();

    fireEvent.change(screen.getByLabelText('기간(개월)'), { target: { value: '601' } });

    // 서버가 @Max(600) 으로 막는다 — 화면이 먼저 막아 400 왕복을 줄인다.
    expect(screen.getByTestId('term-hint')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '계산' })).toBeDisabled();
  });

  it('원금이 0 이면 계산할 수 없다', () => {
    openTab();

    fireEvent.change(screen.getByLabelText('원금'), { target: { value: '0' } });

    expect(screen.getByRole('button', { name: '계산' })).toBeDisabled();
  });

  it('실패하면 앞선 결과를 지우고 오류를 보여 준다', async () => {
    mocked.simulate.mockResolvedValue(schedule());
    openTab();
    fireEvent.click(screen.getByRole('button', { name: '계산' }));
    await waitFor(() => expect(screen.getByTestId('repayment-result')).toBeInTheDocument());

    // 낡은 상환표가 남아 있으면 새 조건의 결과로 오해한다.
    mocked.simulate.mockRejectedValue(new Error('boom'));
    fireEvent.click(screen.getByRole('button', { name: '계산' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByTestId('repayment-result')).not.toBeInTheDocument();
  });
});
