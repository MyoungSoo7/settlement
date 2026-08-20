import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MyBalancesPage from '@/pages/MyBalancesPage';
import { pointApi } from '@/api/point';
import { giftCardApi } from '@/api/giftCard';
import { depositApi } from '@/api/deposit';

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

const mockedPoint = vi.mocked(pointApi);
const mockedGiftCard = vi.mocked(giftCardApi);
const mockedDeposit = vi.mocked(depositApi);

beforeEach(() => {
  vi.clearAllMocks();
  mockedPoint.myBalance.mockResolvedValue({ userId: 7, available: 1200 });
  mockedGiftCard.myBalance.mockResolvedValue({ available: 5000 } as never);
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
