import api from './axios';

/** 코스피 상장 기업 */
export interface FinancialCompany {
  stockCode: string;
  corpCode: string | null;
  name: string;
  market: string;
}

export interface FinancialCompanyPage {
  content: FinancialCompany[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 연간 요약 재무제표 + 파생지표(%) — 계산 불가 지표는 null (화면에서 N/A) */
export interface FinancialStatement {
  fiscalYear: number;
  fsDivision: string;   // CFS 연결 / OFS 별도
  currency: string;
  revenue: number | null;
  operatingProfit: number | null;
  netIncome: number | null;
  totalAssets: number | null;
  totalLiabilities: number | null;
  totalEquity: number | null;
  operatingMargin: number | null;
  netMargin: number | null;
  debtRatio: number | null;
  equityRatio: number | null;
  roa: number | null;
  source: string;       // SEED(근사 샘플) / DART(실데이터)
}

export const financialApi = {
  /** 기업 목록/검색. GET /api/financial/companies */
  companies: async (keyword: string, page: number, size = 15): Promise<FinancialCompanyPage> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (keyword.trim()) params.set('keyword', keyword.trim());
    const res = await api.get<FinancialCompanyPage>(`/api/financial/companies?${params}`);
    return res.data;
  },

  /** 기업별 연도 재무제표. GET /api/financial/companies/{stockCode}/statements */
  statements: async (stockCode: string): Promise<FinancialStatement[]> => {
    const res = await api.get<FinancialStatement[]>(`/api/financial/companies/${stockCode}/statements`);
    return res.data;
  },
};
