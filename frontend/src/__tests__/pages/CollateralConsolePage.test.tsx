import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import CollateralConsolePage from '@/pages/CollateralConsolePage';
import { securedLoanApi, DuplicateEnforcementError, type SecuredLoan } from '@/api/securedLoan';

vi.mock('@/api/securedLoan', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/securedLoan')>();
  return {
    ...actual,
    securedLoanApi: { detail: vi.fn(), revalue: vi.fn(), dispose: vi.fn(), subrogate: vi.fn() },
    newIdempotencyKey: vi.fn(() => 'fixed-key'),
  };
});

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

const mocked = vi.mocked(securedLoanApi);

const loan = (collateral: SecuredLoan['collateral'] = {
  collateralId: 3, type: 'REAL_ESTATE', description: '아파트', appraisedValue: 300_000_000,
  status: 'ACTIVE',
}): SecuredLoan => ({
  loanId: 7, productType: 'MORTGAGE', borrowerUserId: 11, borrowerType: 'CORPORATE',
  principal: 200_000_000, outstanding: 180_000_000, termMonths: 60, annualRatePercent: 5.2,
  repaymentMethod: 'EQUAL_PRINCIPAL', creditScore: 820, creditGrade: 'A', status: 'DISBURSED',
  collateral, createdAt: '2026-08-01T00:00:00',
});

const lookup = (id = '7') => {
  fireEvent.change(screen.getByLabelText('대출번호'), { target: { value: id } });
  fireEvent.click(screen.getByRole('button', { name: '대출 조회' }));
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('confirm', vi.fn(() => true));
});
afterEach(() => vi.unstubAllGlobals());

/**
 * 이 콘솔이 지키는 규율.
 *
 * <p>① <b>조회 전에는 조작할 수 없고</b>, 무담보 대출에는 조작 구획이 아예 없다.
 * <p>② 대출번호를 고치면 앞선 조회·결과를 버린다 — 남은 숫자가 다음 판단을 오염시킨다.
 * <p>③ <b>재평가는 판정을 동반하는 조작</b>이라 결과를 그대로 드러낸다(마진콜 요구액 포함).
 * <p>④ 처분·대위변제는 확인을 받고, <b>409(중복 선점)를 실패로 뭉개지 않는다</b>.
 * <p>⑤ 상각액을 숨기지 않는다 — "회수했다"만 남으면 손실이 장부에서만 보인다.
 */
describe('CollateralConsolePage — 조회', () => {
  it('조회 전에는 재평가·실행 구획이 없다', () => {
    render(<CollateralConsolePage />);

    expect(screen.queryByTestId('revalue-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('enforce-panel')).not.toBeInTheDocument();
  });

  it('무담보 대출이면 조작 구획 대신 이유를 보여 준다', async () => {
    mocked.detail.mockResolvedValue(loan(null));
    render(<CollateralConsolePage />);

    lookup();

    await waitFor(() => expect(screen.getByTestId('no-collateral')).toBeInTheDocument());
    expect(screen.queryByTestId('revalue-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('enforce-panel')).not.toBeInTheDocument();
  });

  it('없는 대출은 없다고 말한다', async () => {
    mocked.detail.mockResolvedValue(null);
    render(<CollateralConsolePage />);

    lookup('99');

    await waitFor(() => expect(screen.getByTestId('loan-missing')).toBeInTheDocument());
  });

  it('대출번호를 고치면 조회 결과를 버린다', async () => {
    mocked.detail.mockResolvedValue(loan());
    render(<CollateralConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('revalue-panel')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('대출번호'), { target: { value: '8' } });

    expect(screen.queryByTestId('revalue-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('loan-summary')).not.toBeInTheDocument();
  });
});

describe('CollateralConsolePage — 재평가', () => {
  const openLoan = async () => {
    mocked.detail.mockResolvedValue(loan());
    render(<CollateralConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('revalue-panel')).toBeInTheDocument());
  };

  it('마진콜 판정이면 추가담보 요구액까지 보여 준다', async () => {
    await openLoan();
    mocked.revalue.mockResolvedValue({
      loanId: 7, collateralId: 3, revaluedValue: 200_000_000,
      coverageRatio: 1.11, outcome: 'MARGIN_CALL', requiredAmount: 52_000_000,
    });

    fireEvent.change(screen.getByLabelText('새 평가액'), { target: { value: '200000000' } });
    fireEvent.click(screen.getByRole('button', { name: '재평가하고 판정' }));

    await waitFor(() => expect(screen.getByTestId('revalue-outcome')).toHaveTextContent('마진콜'));
    expect(screen.getByTestId('revalue-outcome')).toHaveTextContent('111.0%');
    // 요구액을 숨기면 "마진콜입니다"만 남아 운영자가 얼마를 받아야 하는지 모른다.
    expect(screen.getByTestId('required-amount')).toHaveTextContent('52,000,000');
  });

  it('충족 판정에는 요구액이 없다', async () => {
    await openLoan();
    mocked.revalue.mockResolvedValue({
      loanId: 7, collateralId: 3, revaluedValue: 400_000_000,
      coverageRatio: 2.22, outcome: 'SUFFICIENT', requiredAmount: null,
    });

    fireEvent.change(screen.getByLabelText('새 평가액'), { target: { value: '400000000' } });
    fireEvent.click(screen.getByRole('button', { name: '재평가하고 판정' }));

    await waitFor(() => expect(screen.getByTestId('revalue-outcome')).toHaveTextContent('충족'));
    expect(screen.queryByTestId('required-amount')).not.toBeInTheDocument();
  });

  it('평가액이 0 이하면 재평가할 수 없다', async () => {
    await openLoan();

    fireEvent.change(screen.getByLabelText('새 평가액'), { target: { value: '0' } });

    expect(screen.getByRole('button', { name: '재평가하고 판정' })).toBeDisabled();
  });

  it('재평가 후 대출을 다시 읽는다 — 옛 담보값이 남으면 다음 판단이 틀어진다', async () => {
    await openLoan();
    mocked.revalue.mockResolvedValue({
      loanId: 7, collateralId: 3, revaluedValue: 400_000_000,
      coverageRatio: 2.22, outcome: 'SUFFICIENT', requiredAmount: null,
    });

    fireEvent.change(screen.getByLabelText('새 평가액'), { target: { value: '400000000' } });
    fireEvent.click(screen.getByRole('button', { name: '재평가하고 판정' }));

    await waitFor(() => expect(mocked.detail).toHaveBeenCalledTimes(2));
  });
});

describe('CollateralConsolePage — 실행', () => {
  const openLoan = async () => {
    mocked.detail.mockResolvedValue(loan());
    render(<CollateralConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('enforce-panel')).toBeInTheDocument());
  };

  it('처분은 확인을 받고, 취소하면 부르지 않는다', async () => {
    await openLoan();
    vi.stubGlobal('confirm', vi.fn(() => false));

    fireEvent.change(screen.getByLabelText('매각대금'), { target: { value: '150000000' } });
    fireEvent.click(screen.getByRole('button', { name: '담보 처분' }));

    expect(mocked.dispose).not.toHaveBeenCalled();
  });

  it('처분은 멱등 키와 함께 호출되고 회수·상각을 모두 보여 준다', async () => {
    await openLoan();
    mocked.dispose.mockResolvedValue({
      loanId: 7, recovered: 150_000_000, surplus: 0, writtenOff: 30_000_000,
      finalStatus: 'WRITTEN_OFF',
    });

    fireEvent.change(screen.getByLabelText('매각대금'), { target: { value: '150000000' } });
    fireEvent.click(screen.getByRole('button', { name: '담보 처분' }));

    await waitFor(() => expect(mocked.dispose).toHaveBeenCalledWith(7, 150_000_000, 'fixed-key'));
    // API 가 불린 시점과 결과가 렌더에 반영된 시점 사이에 상태 갱신 한 틱이 있다 —
    // 여기서 동기 getBy* 를 쓰면 로컬에선 통과하고 CI 러너에서만 랜덤하게 깨진다.
    expect(await screen.findByTestId('recovered')).toHaveTextContent('150,000,000');
    // 상각을 숨기면 "회수했다"만 남아 손실이 화면에서 사라진다.
    expect(screen.getByTestId('written-off')).toHaveTextContent('30,000,000');
  });

  it('대위변제는 매각대금 없이 호출된다', async () => {
    await openLoan();
    mocked.subrogate.mockResolvedValue({
      loanId: 7, recovered: 153_000_000, surplus: 0, writtenOff: 27_000_000,
      finalStatus: 'SUBROGATED',
    });

    fireEvent.click(screen.getByRole('button', { name: '대위변제 청구' }));

    await waitFor(() => expect(mocked.subrogate).toHaveBeenCalledWith(7, 'fixed-key'));
  });

  it('409(중복 선점)는 실패 문구가 아니라 "이미 처리됨"으로 안내한다', async () => {
    // 실패로 뭉개면 운영자가 다시 집행하려 든다 — 원 요청은 성공했을 수 있다.
    await openLoan();
    mocked.subrogate.mockRejectedValue(new DuplicateEnforcementError());

    fireEvent.click(screen.getByRole('button', { name: '대위변제 청구' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('이미 처리된 요청'));
    expect(screen.queryByTestId('enforce-result')).not.toBeInTheDocument();
  });
});
