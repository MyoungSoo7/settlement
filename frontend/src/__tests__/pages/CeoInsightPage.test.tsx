import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CeoInsightPage from '@/pages/CeoInsightPage';
import { ceoApi } from '@/api/ceo';

vi.mock('@/api/financial', () => ({ financialApi: { companies: vi.fn(), statements: vi.fn() } }));
vi.mock('@/api/ceo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/ceo')>();
  return { ...actual, ceoApi: { insight: vi.fn(), searchCompanies: vi.fn() } };
});

const mockedCeo = vi.mocked(ceoApi);

const company = { stockCode: '005930', corpCode: '00126380', name: '삼성전자', market: 'KOSPI' };

const companyPage = (over: Record<string, unknown> = {}) =>
  ({ content: [company], page: 0, size: 15, totalElements: 1, totalPages: 1, ...over }) as never;

const insight = (over: Record<string, unknown> = {}) =>
  ({
    company,
    companyProfile: null,
    statements: [
      {
        fiscalYear: 2025,
        fsDivision: 'CFS',
        currency: 'KRW',
        revenue: 300_0000_0000_0000,
        operatingProfit: 30_0000_0000_0000,
        netIncome: 25_0000_0000_0000,
        totalAssets: 500_0000_0000_0000,
        totalLiabilities: 100_0000_0000_0000,
        totalEquity: 400_0000_0000_0000,
        operatingMargin: 10,
        netMargin: 8.3,
        debtRatio: 25,
        equityRatio: 80,
        roa: 5,
        source: 'DART',
      },
    ],
    latestStatement: {
      fiscalYear: 2025,
      fsDivision: 'CFS',
      currency: 'KRW',
      revenue: 300_0000_0000_0000,
      operatingProfit: 30_0000_0000_0000,
      netIncome: 25_0000_0000_0000,
      totalAssets: 500_0000_0000_0000,
      totalLiabilities: 100_0000_0000_0000,
      totalEquity: 400_0000_0000_0000,
      operatingMargin: 10,
      netMargin: 8.3,
      debtRatio: 25,
      equityRatio: 80,
      roa: 5,
      source: 'DART',
    },
    reputation: {
      stockCode: '005930',
      snapshotDate: '2026-08-14',
      score: 72,
      grade: 'B',
      articleCount: 20,
      positiveCount: 12,
      negativeCount: 3,
      neutralCount: 5,
      negativeByCategory: {},
      calculatedAt: '2026-08-14T00:00:00Z',
    },
    articles: [
      {
        title: '삼성전자 2분기 실적 발표',
        summary: '영업이익 증가',
        url: 'https://news.example.com/1',
        publisher: '한국경제',
        publishedAt: '2026-08-12T00:00:00Z',
      },
    ],
    documents: [],
    indicators: [],
    marketQuote: {
      baseDate: '2026-08-14',
      closePrice: 71500,
      openPrice: null,
      highPrice: null,
      lowPrice: null,
      priorDayDiff: null,
      fluctuationRate: null,
      volume: null,
      tradeAmount: null,
      listedShares: null,
      marketCap: 426_8394_5232_5000,
      source: 'EXCHANGE',
    },
    valuation: { marketCap: 426_8394_5232_5000, per: 17.1, pbr: 1.07 },
    briefing: {
      headline: '수익성 양호, 평판 보통',
      summaryCards: [
        { label: '매출', value: '300조', hint: '전년 대비 증가', tone: 'good' },
      ],
      risks: [
        {
          category: '평판',
          title: '부정 기사 3건',
          severity: 'LOW',
          evidence: ['소송 관련 보도'],
          interpretation: '단기 영향은 제한적',
          action: '모니터링 유지',
        },
      ],
    },
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedCeo.searchCompanies.mockResolvedValue(companyPage());
  mockedCeo.insight.mockResolvedValue(insight());
});

const openCompany = async () => {
  render(<CeoInsightPage />);
  await userEvent.click(await screen.findByText('삼성전자'));
};

describe('CeoInsightPage — 목록', () => {
  it('기업 목록을 읽어 보여 준다', async () => {
    render(<CeoInsightPage />);

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(mockedCeo.searchCompanies).toHaveBeenCalled();
  });

  it('검색은 첫 페이지부터 다시 조회한다', async () => {
    render(<CeoInsightPage />);
    await screen.findByText('삼성전자');

    await userEvent.type(screen.getByPlaceholderText('기업명 또는 종목코드'), '삼성');
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() =>
      expect(mockedCeo.searchCompanies.mock.calls.at(-1)?.[0]).toBe('삼성'),
    );
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    mockedCeo.searchCompanies.mockResolvedValue(companyPage({ content: [], totalElements: 0 }));
    render(<CeoInsightPage />);

    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('선택 전에는 브리핑 생성 안내를 보여 준다', async () => {
    render(<CeoInsightPage />);

    expect(await screen.findByText('기업을 선택하면 CEO 브리핑이 생성됩니다.')).toBeInTheDocument();
  });
});

describe('CeoInsightPage — 브리핑', () => {
  it('기업을 고르면 인사이트를 모아 헤드라인·요약·리스크를 보여 준다', async () => {
    await openCompany();

    expect(await screen.findByText('수익성 양호, 평판 보통')).toBeInTheDocument();
    expect(screen.getByText('부정 기사 3건')).toBeInTheDocument();
    expect(screen.getByText('모니터링 유지')).toBeInTheDocument();
    expect(mockedCeo.insight).toHaveBeenCalledWith(company);
  });

  it('시총·PER·PBR 을 소비측 조인 결과로 함께 보여 준다', async () => {
    await openCompany();

    expect(await screen.findByText('시가총액')).toBeInTheDocument();
    expect(screen.getByText('시총/순이익')).toBeInTheDocument();
    expect(screen.getByText('시총/자본총계')).toBeInTheDocument();
  });

  it('평판 스냅샷이 없으면 그 사실을 알린다', async () => {
    mockedCeo.insight.mockResolvedValue(insight({ reputation: null }));
    await openCompany();

    expect(await screen.findByText('평판 스냅샷이 아직 없습니다.')).toBeInTheDocument();
  });

  it('기사가 없으면 그 사실을 알린다', async () => {
    mockedCeo.insight.mockResolvedValue(insight({ articles: [] }));
    await openCompany();

    expect(await screen.findByText('수집된 기사가 없습니다.')).toBeInTheDocument();
  });

  it('재무제표가 없어도 브리핑은 그려진다', async () => {
    mockedCeo.insight.mockResolvedValue(insight({ statements: [], latestStatement: null }));
    await openCompany();

    expect(await screen.findByText('등록된 재무제표가 없습니다.')).toBeInTheDocument();
  });

  it('인사이트 조회 실패는 사유를 보여 준다', async () => {
    mockedCeo.insight.mockRejectedValue(new Error('down'));
    await openCompany();

    expect(await screen.findByText('CEO 인사이트 조회에 실패했습니다.')).toBeInTheDocument();
  });
});
