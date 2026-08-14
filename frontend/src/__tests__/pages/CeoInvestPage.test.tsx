import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CeoInvestPage from '@/pages/CeoInvestPage';
import { financialApi } from '@/api/financial';
import { investmentApi } from '@/api/investment';

// 실시간 시세 티커는 SSE(EventSource)를 여는데 jsdom 에는 그 API 가 없다.
// 이 화면의 관심사(점수·재원·주문)와도 무관하므로 자리만 비워 둔다 — 티커 자체는 별도 테스트가 있다.
vi.mock('@/components/LiveQuoteTicker', () => ({ default: () => null }));

vi.mock('@/api/financial', () => ({ financialApi: { companies: vi.fn(), statements: vi.fn() } }));
vi.mock('@/api/investment', () => ({
  investmentApi: {
    score: vi.fn(),
    funding: vi.fn(),
    createOrder: vi.fn(),
    execute: vi.fn(),
    cancel: vi.fn(),
    ordersBySeller: vi.fn(),
    recommendations: vi.fn(),
  },
}));

const mockedFinancial = vi.mocked(financialApi);
const mockedInvestment = vi.mocked(investmentApi);

const company = { stockCode: '005930', corpCode: '00126380', name: '삼성전자', market: 'KOSPI' };

const companyPage = (over: Record<string, unknown> = {}) =>
  ({ content: [company], page: 0, size: 10, totalElements: 1, totalPages: 1, ...over }) as never;

const score = (over: Record<string, unknown> = {}) =>
  ({
    stockCode: '005930',
    companyName: '삼성전자',
    market: 'KOSPI',
    fiscalYear: 2025,
    totalScore: 82,
    grade: 'AA',
    eligible: true,
    profitability: { score: 28, maxScore: 35 },
    stability: { score: 27, maxScore: 35 },
    growth: { score: 27, maxScore: 30 },
    improvements: [],
    ...over,
  }) as never;

const funding = (over: Record<string, unknown> = {}) =>
  ({ sellerId: 1, settledAmount: 10_000_000, investedAmount: 2_000_000, availableAmount: 8_000_000, ...over }) as never;

const order = (over: Record<string, unknown> = {}) =>
  ({
    id: 7,
    sellerId: 1,
    stockCode: '005930',
    companyName: '삼성전자',
    amount: 1_000_000,
    gradeAtOrder: 'AA',
    status: 'REQUESTED',
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedFinancial.companies.mockResolvedValue(companyPage());
  mockedInvestment.score.mockResolvedValue(score());
  mockedInvestment.funding.mockResolvedValue(funding());
  mockedInvestment.ordersBySeller.mockResolvedValue([] as never);
});

const selectCompany = async () => {
  render(<CeoInvestPage />);
  await userEvent.click(await screen.findByText('삼성전자'));
};

describe('CeoInvestPage — 종목 선택·점수', () => {
  it('종목 목록을 읽어 보여 준다', async () => {
    render(<CeoInvestPage />);

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(mockedFinancial.companies).toHaveBeenCalledWith('', 0, 10);
  });

  it('검색은 첫 페이지부터 다시 조회한다', async () => {
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');

    await userEvent.type(screen.getByPlaceholderText('기업명 또는 종목코드'), '삼성');
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mockedFinancial.companies).toHaveBeenLastCalledWith('삼성', 0, 10));
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage({ content: [], totalElements: 0 }));
    render(<CeoInvestPage />);

    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('목록 조회 실패는 사유를 보여 준다', async () => {
    mockedFinancial.companies.mockRejectedValue(new Error('down'));
    render(<CeoInvestPage />);

    expect(await screen.findByText('기업 목록 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('종목을 고르면 투자점수를 읽어 등급과 함께 보여 준다', async () => {
    await selectCompany();

    expect(await screen.findByText('AA')).toBeInTheDocument();
    expect(screen.getByText('82')).toBeInTheDocument();
    expect(mockedInvestment.score).toHaveBeenCalledWith('005930');
  });

  it('점수 조회 실패는 사유를 보여 준다', async () => {
    mockedInvestment.score.mockRejectedValue(new Error('down'));
    await selectCompany();

    expect(await screen.findByText('투자 점수 조회에 실패했습니다.')).toBeInTheDocument();
  });
});

describe('CeoInvestPage — 재원·주문', () => {
  it('셀러 ID로 재원과 주문을 함께 읽는다', async () => {
    mockedInvestment.ordersBySeller.mockResolvedValue([order()] as never);
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mockedInvestment.funding).toHaveBeenCalledWith(1));
    expect(mockedInvestment.ordersBySeller).toHaveBeenCalledWith(1);
    expect(await screen.findByText('투자 가능')).toBeInTheDocument();
  });

  it('재원 조회 실패는 사유를 보여 준다', async () => {
    mockedInvestment.funding.mockRejectedValue(new Error('down'));
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '조회' }));

    expect(await screen.findByText('투자 재원 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('주문 목록 조회 실패도 사유를 보여 준다', async () => {
    mockedInvestment.ordersBySeller.mockRejectedValue(new Error('down'));
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '조회' }));

    expect(await screen.findByText('투자 주문 목록 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('종목을 고르기 전에는 주문 버튼이 잠긴다', async () => {
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');

    expect(screen.getByRole('button', { name: '종목 먼저 선택' })).toBeDisabled();
  });

  it('주문하면 생성 결과를 안내하고 재원·목록을 다시 읽는다', async () => {
    mockedInvestment.createOrder.mockResolvedValue(order({ id: 9 }));
    await selectCompany();
    await screen.findByText('AA');

    fireEvent.click(screen.getByRole('button', { name: /삼성전자 투자 주문/ }));

    await waitFor(() =>
      expect(mockedInvestment.createOrder).toHaveBeenCalledWith({
        sellerId: 1,
        stockCode: '005930',
        amount: 1_000_000,
      }),
    );
    expect(await screen.findByText(/투자 주문 생성 — #9/)).toBeInTheDocument();
    expect(mockedInvestment.funding).toHaveBeenCalled();
  });

  it('투자 부적격·재원 부족(422)은 그 사유를 구분해 보여 준다', async () => {
    mockedInvestment.createOrder.mockRejectedValue({
      response: { status: 422, data: { message: '투자 재원이 부족합니다' } },
    });
    await selectCompany();
    await screen.findByText('AA');

    fireEvent.click(screen.getByRole('button', { name: /삼성전자 투자 주문/ }));

    expect(await screen.findByText('투자 재원이 부족합니다')).toBeInTheDocument();
  });

  it('그 밖의 주문 실패는 일반 문구로 알린다', async () => {
    mockedInvestment.createOrder.mockRejectedValue(new Error('down'));
    await selectCompany();
    await screen.findByText('AA');

    fireEvent.click(screen.getByRole('button', { name: /삼성전자 투자 주문/ }));

    expect(await screen.findByText('투자 주문 생성에 실패했습니다.')).toBeInTheDocument();
  });
});

describe('CeoInvestPage — 주문 집행·취소', () => {
  const loadOrders = async (status = 'REQUESTED') => {
    mockedInvestment.ordersBySeller.mockResolvedValue([order({ status })] as never);
    render(<CeoInvestPage />);
    await screen.findByText('삼성전자');
    await userEvent.click(screen.getByRole('button', { name: '조회' }));
    await screen.findByText('#7');
  };

  it('요청 상태 주문은 집행·취소할 수 있다', async () => {
    mockedInvestment.execute.mockResolvedValue(order({ status: 'EXECUTED' }));
    await loadOrders();

    fireEvent.click(screen.getByRole('button', { name: '집행' }));

    await waitFor(() => expect(mockedInvestment.execute).toHaveBeenCalledWith(7));
    expect(await screen.findByText(/#7 집행 완료 — EXECUTED/)).toBeInTheDocument();
  });

  it('취소도 같은 경로를 탄다', async () => {
    mockedInvestment.cancel.mockResolvedValue(order({ status: 'CANCELED' }));
    await loadOrders();

    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(mockedInvestment.cancel).toHaveBeenCalledWith(7));
    expect(await screen.findByText(/#7 취소 완료 — CANCELED/)).toBeInTheDocument();
  });

  it('조건 미충족(422)은 그 사유를 구분해 알린다', async () => {
    mockedInvestment.execute.mockRejectedValue({
      response: { status: 422, data: { message: '이미 집행된 주문입니다' } },
    });
    await loadOrders();

    fireEvent.click(screen.getByRole('button', { name: '집행' }));

    expect(await screen.findByText('이미 집행된 주문입니다')).toBeInTheDocument();
  });

  it('그 밖의 실패는 일반 문구로 알린다', async () => {
    mockedInvestment.execute.mockRejectedValue(new Error('down'));
    await loadOrders();

    fireEvent.click(screen.getByRole('button', { name: '집행' }));

    expect(await screen.findByText('주문 처리에 실패했습니다.')).toBeInTheDocument();
  });

  it('종결 상태 주문에는 조작 버튼이 없다', async () => {
    await loadOrders('EXECUTED');

    expect(screen.queryByRole('button', { name: '집행' })).not.toBeInTheDocument();
  });
});
