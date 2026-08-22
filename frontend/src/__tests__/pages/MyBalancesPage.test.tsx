import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MyBalancesPage from '@/pages/MyBalancesPage';
import { pointApi } from '@/api/point';
import { giftCardApi } from '@/api/giftCard';
import { depositApi } from '@/api/deposit';
import { sellerBankAccountApi } from '@/api/sellerBankAccount';

/**
 * 예치금 구획이 지켜야 하는 규율은 하나다: <b>"계좌가 없다"와 "잔고가 0이다"를 뭉개지 않는다</b>.
 *
 * <p>서버가 굳이 404 로 나눠 준 구분인데, 화면이 0원 카드를 그리면 그 구분이 사라진다 —
 * 셀러가 아닌 사용자에게는 "내 예치금은 0원"이라는 틀린 사실이 되고, 셀러에게는 계좌 미개설과
 * 잔고 소진이 같은 화면이 된다.
 *
 * <p>두 번째 규율: available 과 locked 를 함께 보여 준다. 합계만 보이면 "잔고는 있는데 왜
 * 결제가 막히나"에 화면이 답하지 못한다.
 */

vi.mock('@/api/point', () => ({ pointApi: { myBalance: vi.fn() } }));
vi.mock('@/api/giftCard', () => ({ giftCardApi: { myBalance: vi.fn(), redeem: vi.fn() } }));
vi.mock('@/api/deposit', () => ({ depositApi: { myAccount: vi.fn(), accountOf: vi.fn() } }));
vi.mock('@/api/sellerBankAccount', () => ({
  sellerBankAccountApi: { mine: vi.fn(), saveMine: vi.fn(), of: vi.fn(), save: vi.fn() },
}));

const mockedPoint = vi.mocked(pointApi);
const mockedGiftCard = vi.mocked(giftCardApi);
const mockedDeposit = vi.mocked(depositApi);
const mockedBankAccount = vi.mocked(sellerBankAccountApi);

beforeEach(() => {
  vi.clearAllMocks();
  mockedPoint.myBalance.mockResolvedValue({ userId: 7, available: 1200 });
  mockedGiftCard.myBalance.mockResolvedValue({ available: 5000 } as never);
  mockedBankAccount.mine.mockResolvedValue(null);
});

describe('MyBalancesPage — 예치금', () => {
  it('계좌가 없으면(서버 404 → null) 예치금 구획 자체를 그리지 않는다', async () => {
    mockedDeposit.myAccount.mockResolvedValue(null);

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('point-balance')).toHaveTextContent('1,200P'));
    // 0원 카드로 그리면 "계좌가 열린 적 없다"가 "잔고가 0이다"로 둔갑한다.
    expect(screen.queryByTestId('deposit-section')).not.toBeInTheDocument();
  });

  it('계좌가 있으면 사용 가능액·묶인 금액·합계를 따로 보여 준다', async () => {
    mockedDeposit.myAccount.mockResolvedValue({
      id: 1, sellerId: 7, available: 80000, locked: 20000, total: 100000,
      createdAt: '2026-08-01T00:00:00', updatedAt: '2026-08-20T00:00:00',
    });

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('deposit-section')).toBeInTheDocument());
    expect(screen.getByTestId('deposit-available')).toHaveTextContent('80,000원');
    // 묶인 금액을 숨기면 "잔고 10만원인데 왜 8만원만 쓰이나"를 화면이 설명하지 못한다.
    expect(screen.getByTestId('deposit-locked')).toHaveTextContent('20,000원');
    expect(screen.getByTestId('deposit-total')).toHaveTextContent('100,000원');
  });

  it('예치금 조회는 셀러 식별자를 보내지 않는다 — 서버가 JWT 에서만 파생한다', async () => {
    mockedDeposit.myAccount.mockResolvedValue(null);

    render(<MyBalancesPage />);

    await waitFor(() => expect(mockedDeposit.myAccount).toHaveBeenCalled());
    // 인자를 실어 보낼 자리가 있으면 그 자리가 곧 IDOR 경로가 된다.
    expect(mockedDeposit.myAccount).toHaveBeenCalledWith();
  });
});

/**
 * 지급 계좌 구획의 규율.
 *
 * <p>핵심은 <b>경고를 언제 띄우는가</b>다. 계좌가 없다는 사실 자체는 일반 구매자에게 아무 문제가
 * 아니다. 그런데 예치 계좌가 있는 사용자(= 셀러가 확실한 사람)에게는 "정산금이 지금 안 나가고
 * 있다"는 뜻이 된다 — 서버는 이때 payout 을 만들지 않고 WARN 만 남기므로, 화면이 말해 주지
 * 않으면 셀러는 원인을 알 수 없다.
 */
describe('MyBalancesPage — 지급 계좌', () => {
  const registered = {
    sellerId: 7, bank: 'KB', account: '****1234', holder: '홍길동',
    updatedAt: '2026-08-21T00:00:00Z',
  };

  it('셀러(예치 계좌 있음)인데 지급 계좌가 없으면 지급이 막혔다고 경고한다', async () => {
    mockedDeposit.myAccount.mockResolvedValue({
      id: 1, sellerId: 7, available: 80000, locked: 0, total: 80000,
      createdAt: '2026-08-01T00:00:00', updatedAt: '2026-08-20T00:00:00',
    });
    mockedBankAccount.mine.mockResolvedValue(null);

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('payout-blocked-warning')).toBeInTheDocument());
    expect(screen.getByTestId('payout-blocked-warning')).toHaveTextContent('정산금이 지급되지 않습니다');
  });

  it('셀러가 아니면 계좌가 없어도 경고하지 않는다 — 구매자에겐 문제가 아니다', async () => {
    mockedDeposit.myAccount.mockResolvedValue(null);
    mockedBankAccount.mine.mockResolvedValue(null);

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('bank-account-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('payout-blocked-warning')).not.toBeInTheDocument();
  });

  it('셀러라도 계좌가 등록돼 있으면 경고하지 않고 마스킹된 현재 계좌를 보여 준다', async () => {
    mockedDeposit.myAccount.mockResolvedValue({
      id: 1, sellerId: 7, available: 80000, locked: 0, total: 80000,
      createdAt: '2026-08-01T00:00:00', updatedAt: '2026-08-20T00:00:00',
    });
    mockedBankAccount.mine.mockResolvedValue(registered);

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('bank-account-current')).toHaveTextContent('****1234'));
    expect(screen.queryByTestId('payout-blocked-warning')).not.toBeInTheDocument();
  });

  it('계좌 조회가 실패해도 포인트·기프트카드 잔액은 그대로 보인다', async () => {
    // 같은 Promise.all 에 묶으면 계좌 조회 실패(구 토큰이면 403)가 멀쩡한 잔액까지 지운다.
    mockedDeposit.myAccount.mockResolvedValue(null);
    mockedBankAccount.mine.mockRejectedValue(new Error('403'));

    render(<MyBalancesPage />);

    await waitFor(() => expect(screen.getByTestId('point-balance')).toHaveTextContent('1,200P'));
    expect(screen.getByTestId('giftcard-balance')).toHaveTextContent('5,000원');
  });
});
