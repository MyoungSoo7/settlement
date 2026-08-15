import { describe, it, expect, vi, beforeEach } from 'vitest';
import { financialApi } from '@/api/financial';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const page = {
  content: [{ stockCode: '005930', corpCode: '00126380', name: '삼성전자', market: 'KOSPI' }],
  page: 0,
  size: 15,
  totalElements: 1,
  totalPages: 1,
};

describe('financialApi.companies', () => {
  beforeEach(() => vi.resetAllMocks());

  it('키워드가 비면 keyword 파라미터를 붙이지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: page });

    await financialApi.companies('', 0);

    expect(api.get).toHaveBeenCalledWith('/api/financial/companies?page=0&size=15');
  });

  it('공백만 있는 키워드도 검색어로 보내지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: page });

    await financialApi.companies('   ', 1);

    expect(api.get).toHaveBeenCalledWith('/api/financial/companies?page=1&size=15');
  });

  it('키워드는 트리밍해서 붙인다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: page });

    const result = await financialApi.companies(' 삼성 ', 0);

    expect(api.get).toHaveBeenCalledWith(
      `/api/financial/companies?page=0&size=15&keyword=${encodeURIComponent('삼성')}`,
    );
    expect(result.content[0].name).toBe('삼성전자');
  });

  it('페이지 크기를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...page, size: 5 } });

    await financialApi.companies('', 2, 5);

    expect(api.get).toHaveBeenCalledWith('/api/financial/companies?page=2&size=5');
  });
});

describe('financialApi.statements', () => {
  beforeEach(() => vi.resetAllMocks());

  it('기업별 연도 재무제표를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [
        {
          fiscalYear: 2025,
          fsDivision: 'CFS',
          currency: 'KRW',
          revenue: 300_000_000_000_000,
          operatingProfit: 30_000_000_000_000,
          netIncome: 25_000_000_000_000,
          totalAssets: 500_000_000_000_000,
          totalLiabilities: 100_000_000_000_000,
          totalEquity: 400_000_000_000_000,
          operatingMargin: 10,
          netMargin: 8.3,
          debtRatio: 25,
          equityRatio: 80,
          roa: 5,
          source: 'DART',
        },
      ],
    });

    const result = await financialApi.statements('005930');

    expect(api.get).toHaveBeenCalledWith('/api/financial/companies/005930/statements');
    expect(result[0].source).toBe('DART');
  });

  it('계산 불가 지표는 null 로 내려오며 그대로 보존한다 (화면에서 N/A)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [
        {
          fiscalYear: 2024,
          fsDivision: 'OFS',
          currency: 'KRW',
          revenue: null,
          operatingProfit: null,
          netIncome: null,
          totalAssets: null,
          totalLiabilities: null,
          totalEquity: null,
          operatingMargin: null,
          netMargin: null,
          debtRatio: null,
          equityRatio: null,
          roa: null,
          source: 'SEED',
        },
      ],
    });

    const result = await financialApi.statements('000660');

    expect(result[0].operatingMargin).toBeNull();
    expect(result[0].roa).toBeNull();
  });
});
