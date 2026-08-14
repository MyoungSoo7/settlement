import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CeoAccountPage from '@/pages/CeoAccountPage';
import CeoInvestRecommendPage from '@/pages/CeoInvestRecommendPage';
import GamePage from '@/pages/GamePage';
import { accountApi } from '@/api/account';
import { investmentApi } from '@/api/investment';

vi.mock('@/api/account', () => ({
  accountApi: {
    loanAggregate: vi.fn(),
    investmentAggregate: vi.fn(),
    settlementAggregate: vi.fn(),
    trialBalance: vi.fn(),
    ownerAccounts: vi.fn(),
  },
}));
vi.mock('@/api/investment', () => ({
  investmentApi: { recommendations: vi.fn() },
}));

const mockedAccount = vi.mocked(accountApi);
const mockedInvestment = vi.mocked(investmentApi);

const trialBalance = (over: Record<string, unknown> = {}) =>
  ({
    accounts: [
      { account: 'CASH', debitTotal: 1_000_000, creditTotal: 0 },
      { account: 'SELLER_PAYABLE', debitTotal: 0, creditTotal: 1_000_000 },
    ],
    totalDebit: 1_000_000,
    totalCredit: 1_000_000,
    balanced: true,
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedAccount.loanAggregate.mockResolvedValue({
    disbursedTotal: 100_000_000,
    repaidTotal: 40_000_000,
    outstanding: 60_000_000,
    corporateDisbursedTotal: 30_000_000,
    corporateOutstanding: 25_000_000,
    entryCount: 12,
  } as never);
  mockedAccount.investmentAggregate.mockResolvedValue({
    investedTotal: 12_000_000,
    orderCount: 3,
  } as never);
  mockedAccount.settlementAggregate.mockResolvedValue({
    scheduledTotal: 5_000_000,
    confirmedTotal: 3_000_000,
    pendingScheduled: 2_000_000,
  } as never);
  mockedAccount.trialBalance.mockResolvedValue(trialBalance());
});

describe('CeoAccountPage — 집계·시산표', () => {
  it('대출·투자·정산 집계와 시산표를 함께 읽는다', async () => {
    render(<CeoAccountPage />);

    expect(await screen.findByText('계정계 현황')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('차·대 일치')).toBeInTheDocument());
    expect(mockedAccount.loanAggregate).toHaveBeenCalled();
    expect(mockedAccount.trialBalance).toHaveBeenCalled();
  });

  it('차·대가 어긋나면 불일치로 드러낸다 (원장 불변식 위반은 숨기지 않는다)', async () => {
    mockedAccount.trialBalance.mockResolvedValue(
      trialBalance({ totalCredit: 900_000, balanced: false }),
    );
    render(<CeoAccountPage />);

    expect(await screen.findByText('불일치')).toBeInTheDocument();
  });

  it('분개가 없으면 그 사실을 알린다', async () => {
    mockedAccount.trialBalance.mockResolvedValue(
      trialBalance({ accounts: [], totalDebit: 0, totalCredit: 0 }),
    );
    render(<CeoAccountPage />);

    expect(await screen.findByText('원장 분개가 없습니다.')).toBeInTheDocument();
  });

  it('집계 조회 실패는 사유를 보여 준다', async () => {
    mockedAccount.loanAggregate.mockRejectedValue(new Error('down'));
    render(<CeoAccountPage />);

    expect(await screen.findByText('계정계 집계 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('소유자 계정 잔액을 유형·ID 로 조회한다', async () => {
    mockedAccount.ownerAccounts.mockResolvedValue({
      ownerType: 'SELLER',
      ownerId: 1,
      balances: [{ account: 'CASH', side: 'DEBIT', balance: 50_000 }],
      entryCount: 4,
    } as never);
    render(<CeoAccountPage />);
    await screen.findByText('차·대 일치');

    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mockedAccount.ownerAccounts).toHaveBeenCalledWith('SELLER', 1));
  });

  it('소유자 조회 실패는 그 영역에만 사유를 남긴다', async () => {
    mockedAccount.ownerAccounts.mockRejectedValue(new Error('down'));
    render(<CeoAccountPage />);
    await screen.findByText('차·대 일치');

    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    expect(await screen.findByText('계정 잔액 조회에 실패했습니다.')).toBeInTheDocument();
  });
});

describe('CeoInvestRecommendPage', () => {
  it('처음에는 조회 버튼만 있고 자동 조회하지 않는다', () => {
    render(<CeoInvestRecommendPage />);

    expect(screen.getByRole('button', { name: '추천 종목 보기' })).toBeInTheDocument();
    expect(mockedInvestment.recommendations).not.toHaveBeenCalled();
  });

  it('조회하면 추천 종목과 매수·손절·익절가를 보여 준다', async () => {
    mockedInvestment.recommendations.mockResolvedValue({
      recommendedDate: '2026-08-14',
      items: [
        {
          stockCode: '005930',
          stockName: '삼성전자',
          sector: '반도체',
          reason: '재무 안정 + 악재 없음',
          entryPrice: 70000,
          stopLossPrice: 65100,
          takeProfitPrice: 84000,
        },
      ],
      priceRule: '평균 매수가 -7% / +20%',
      disclaimer: '참고 자료입니다',
    } as never);
    render(<CeoInvestRecommendPage />);

    await userEvent.click(screen.getByRole('button', { name: '추천 종목 보기' }));

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(screen.getByText('재무 안정 + 악재 없음')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 조회' })).toBeInTheDocument();
  });

  it('추천 세트가 없으면 그 사실을 알린다', async () => {
    mockedInvestment.recommendations.mockResolvedValue({
      recommendedDate: null,
      items: [],
      priceRule: '',
      disclaimer: '',
    } as never);
    render(<CeoInvestRecommendPage />);

    await userEvent.click(screen.getByRole('button', { name: '추천 종목 보기' }));

    expect(await screen.findByText('등록된 추천 세트가 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 재시도 안내와 함께 알린다', async () => {
    mockedInvestment.recommendations.mockRejectedValue(new Error('down'));
    render(<CeoInvestRecommendPage />);

    await userEvent.click(screen.getByRole('button', { name: '추천 종목 보기' }));

    expect(
      await screen.findByText('추천 종목을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
  });
});

describe('GamePage', () => {
  it('기본은 바둑이고 탭으로 오목으로 바꾼다', async () => {
    const { container } = render(<GamePage />);

    expect(container.querySelector('iframe')).toHaveAttribute('src', '/games/baduk');

    await userEvent.click(screen.getByRole('button', { name: '오목' }));

    expect(container.querySelector('iframe')).toHaveAttribute('src', '/games/omok');
  });
});
