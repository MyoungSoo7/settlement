import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EconomicsPage from '@/pages/EconomicsPage';
import FinancialStatementsPage from '@/pages/FinancialStatementsPage';
import { economicsApi } from '@/api/economics';
import { financialApi } from '@/api/financial';

vi.mock('@/api/economics', () => ({
  economicsApi: { indicators: vi.fn(), series: vi.fn() },
}));
vi.mock('@/api/financial', () => ({
  financialApi: { companies: vi.fn(), statements: vi.fn() },
}));

const mockedEconomics = vi.mocked(economicsApi);
const mockedFinancial = vi.mocked(financialApi);

const indicator = (over: Record<string, unknown> = {}) =>
  ({
    code: 'BASE_RATE',
    name: '한국은행 기준금리',
    unit: '%',
    cycle: 'D',
    latest: { observedDate: '2026-08-01', value: 3 },
    change: { amount: -0.25, ratePercent: -7.69 },
    ...over,
  }) as never;

const company = { stockCode: '005930', corpCode: '00126380', name: '삼성전자', market: 'KOSPI' };

const companyPage = (over: Record<string, unknown> = {}) =>
  ({ content: [company], page: 0, size: 15, totalElements: 1, totalPages: 1, ...over }) as never;

const statement = (over: Record<string, unknown> = {}) =>
  ({
    fiscalYear: 2025,
    fsDivision: 'CFS',
    currency: 'KRW',
    revenue: 300_0000_0000_0000,
    operatingProfit: 30_0000_0000_0000,
    netIncome: -5_0000_0000_0000,
    totalAssets: 500_0000_0000_0000,
    totalLiabilities: 100_0000_0000_0000,
    totalEquity: 400_0000_0000_0000,
    operatingMargin: 10,
    netMargin: null,
    debtRatio: 25,
    equityRatio: 80,
    roa: null,
    source: 'DART',
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedEconomics.indicators.mockResolvedValue([indicator()] as never);
  mockedEconomics.series.mockResolvedValue({
    code: 'BASE_RATE',
    name: '한국은행 기준금리',
    unit: '%',
    points: [
      { observedDate: '2026-07-01', value: 3.25, source: 'SEED' },
      { observedDate: '2026-08-01', value: 3, source: 'ECOS' },
    ],
  } as never);
  mockedFinancial.companies.mockResolvedValue(companyPage());
  mockedFinancial.statements.mockResolvedValue([statement()] as never);
});

describe('EconomicsPage', () => {
  it('지표 카드에 최신값·관측일·전기 대비 변동을 보여 준다', async () => {
    render(<EconomicsPage />);

    expect(await screen.findByText('한국은행 기준금리')).toBeInTheDocument();
    expect(screen.getByText('3 %')).toBeInTheDocument();
    expect(screen.getByText('2026-08-01')).toBeInTheDocument();
    expect(screen.getByText(/▼ 0.25 \(-7.69%\)/)).toBeInTheDocument();
  });

  it('상승은 ▲ 로 표시한다 (국내 관례)', async () => {
    mockedEconomics.indicators.mockResolvedValue([
      indicator({ change: { amount: 0.25, ratePercent: 8.33 } }),
    ] as never);
    render(<EconomicsPage />);

    expect(await screen.findByText(/▲ 0.25 \(\+8.33%\)/)).toBeInTheDocument();
  });

  it('최신값이 없으면 N/A 로 표시한다', async () => {
    mockedEconomics.indicators.mockResolvedValue([
      indicator({ latest: null, change: null }),
    ] as never);
    render(<EconomicsPage />);

    expect(await screen.findByText('N/A')).toBeInTheDocument();
    expect(screen.getByText('전기 대비 변동 없음')).toBeInTheDocument();
  });

  it('지표가 없으면 그 사실을 알린다', async () => {
    mockedEconomics.indicators.mockResolvedValue([] as never);
    render(<EconomicsPage />);

    expect(await screen.findByText('등록된 지표가 없습니다')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다', async () => {
    mockedEconomics.indicators.mockRejectedValue(new Error('down'));
    render(<EconomicsPage />);

    expect(await screen.findByText('경제지표 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('지표를 고르면 시계열을 최신순으로 보여 주고 출처 배지를 붙인다', async () => {
    render(<EconomicsPage />);

    await userEvent.click(await screen.findByText('한국은행 기준금리'));

    expect(await screen.findByText('ECOS')).toBeInTheDocument();
    expect(screen.getByText('SEED')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0].textContent).toContain('2026-08-01'); // 최신이 위
    expect(mockedEconomics.series).toHaveBeenCalledWith('BASE_RATE');
  });

  it('시계열이 비면 그 사실을 알린다', async () => {
    mockedEconomics.series.mockResolvedValue({
      code: 'BASE_RATE',
      name: '기준금리',
      unit: '%',
      points: [],
    } as never);
    render(<EconomicsPage />);

    await userEvent.click(await screen.findByText('한국은행 기준금리'));

    expect(await screen.findByText('등록된 시계열이 없습니다')).toBeInTheDocument();
  });

  it('시계열 조회 실패는 사유를 보여 준다', async () => {
    mockedEconomics.series.mockRejectedValue(new Error('down'));
    render(<EconomicsPage />);

    await userEvent.click(await screen.findByText('한국은행 기준금리'));

    expect(await screen.findByText('시계열 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('닫기를 누르면 상세를 접는다', async () => {
    render(<EconomicsPage />);
    await userEvent.click(await screen.findByText('한국은행 기준금리'));
    await screen.findByText('ECOS');

    await userEvent.click(screen.getByRole('button', { name: '닫기 ✕' }));

    expect(screen.queryByText('ECOS')).not.toBeInTheDocument();
  });
});

describe('FinancialStatementsPage', () => {
  it('기업 목록을 읽어 표로 보여 준다', async () => {
    render(<FinancialStatementsPage />);

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(mockedFinancial.companies).toHaveBeenCalledWith('', 0);
  });

  it('검색하면 첫 페이지부터 그 키워드로 다시 조회한다', async () => {
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    await userEvent.type(screen.getByPlaceholderText(/기업명 또는 종목코드 검색/), '삼성');
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mockedFinancial.companies).toHaveBeenLastCalledWith('삼성', 0));
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage({ content: [], totalElements: 0 }));
    render(<FinancialStatementsPage />);

    expect(await screen.findByText('검색 결과가 없습니다')).toBeInTheDocument();
  });

  it('목록 조회 실패는 사유를 보여 준다', async () => {
    mockedFinancial.companies.mockRejectedValue(new Error('down'));
    render(<FinancialStatementsPage />);

    expect(await screen.findByText('기업 목록 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('페이지가 하나면 페이지네이션을 그리지 않는다', async () => {
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument();
  });

  it('여러 페이지면 이동 버튼이 경계에서 잠긴다', async () => {
    mockedFinancial.companies.mockResolvedValue(
      companyPage({ totalElements: 30, totalPages: 2 }),
    );
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() => expect(mockedFinancial.companies).toHaveBeenLastCalledWith('', 1));
  });

  it('재무제표를 열면 금액을 조·억으로 축약하고 계산 불가 지표는 N/A 로 둔다', async () => {
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '재무제표 보기' }));

    expect(await screen.findByText('300조')).toBeInTheDocument();
    expect(screen.getByText('-5조')).toBeInTheDocument();
    expect(screen.getAllByText('N/A').length).toBeGreaterThanOrEqual(1); // roa·netMargin
    expect(screen.getByText('DART')).toBeInTheDocument();
    expect(mockedFinancial.statements).toHaveBeenCalledWith('005930');
  });

  it('재무제표가 없으면 그 사실을 알린다', async () => {
    mockedFinancial.statements.mockResolvedValue([] as never);
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '재무제표 보기' }));

    expect(await screen.findByText('등록된 재무제표가 없습니다')).toBeInTheDocument();
  });

  it('재무제표 조회 실패는 사유를 보여 준다', async () => {
    mockedFinancial.statements.mockRejectedValue(new Error('down'));
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');

    await userEvent.click(screen.getByRole('button', { name: '재무제표 보기' }));

    expect(await screen.findByText('재무제표 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('닫기를 누르면 상세를 접는다', async () => {
    render(<FinancialStatementsPage />);
    await screen.findByText('삼성전자');
    await userEvent.click(screen.getByRole('button', { name: '재무제표 보기' }));
    await screen.findByText('DART');

    await userEvent.click(screen.getByRole('button', { name: '닫기 ✕' }));

    expect(screen.queryByText('DART')).not.toBeInTheDocument();
  });
});
