import api from './axios';

/** 기업 (company-service — 뉴스·평판 조회, ADR 0023) */
export interface Company {
  stockCode: string;
  corpCode: string | null;
  name: string;
  market: string;
}

export interface CompanyPage {
  content: Company[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 평판 스냅샷 — 아직 산정 전이면 null(백엔드 204) */
export interface Reputation {
  stockCode: string;
  snapshotDate: string;
  score: number;
  grade: string;            // A ~ E
  articleCount: number;
  positiveCount: number;
  negativeCount: number;
  neutralCount: number;
  negativeByCategory: Record<string, number>;
  calculatedAt: string;
}

/** 뉴스 기사 메타데이터 (본문 미저장 — 제목·요약·링크) */
export interface Article {
  title: string;
  summary: string | null;
  publisher: string | null;
  url: string;
  source: string;           // NAVER_NEWS 등
  publishedAt: string | null;
}

export interface ArticlePage {
  content: Article[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 기업 문서함 — 외부 파이프라인 산출물(CEO 브리핑 docx 등) 메타데이터 */
export interface CompanyDocument {
  id: number;
  stockCode: string;
  title: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}

export const companyApi = {
  /** 기업 목록/검색. GET /api/company/companies */
  companies: async (keyword: string, page: number, size = 15): Promise<CompanyPage> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (keyword.trim()) params.set('keyword', keyword.trim());
    const res = await api.get<CompanyPage>(`/api/company/companies?${params}`);
    return res.data;
  },

  /** 최신 평판 스냅샷. GET /api/company/companies/{stockCode}/reputation — 미산정 시 204 → null */
  reputation: async (stockCode: string): Promise<Reputation | null> => {
    const res = await api.get<Reputation | ''>(`/api/company/companies/${stockCode}/reputation`);
    return res.status === 204 || !res.data ? null : (res.data as Reputation);
  },

  /** 기업별 기사 목록. GET /api/company/companies/{stockCode}/articles */
  articles: async (stockCode: string, page = 0, size = 20): Promise<ArticlePage> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    const res = await api.get<ArticlePage>(`/api/company/companies/${stockCode}/articles?${params}`);
    return res.data;
  },

  /** 기업 문서함 목록. GET /api/company/companies/{stockCode}/documents */
  documents: async (stockCode: string): Promise<CompanyDocument[]> => {
    const res = await api.get<CompanyDocument[]>(`/api/company/companies/${stockCode}/documents`);
    return res.data;
  },

  /** 문서 다운로드 URL (공개 GET — <a href> 로 바로 사용) */
  documentDownloadUrl: (id: number): string =>
    `${api.defaults.baseURL || ''}/api/company/documents/${id}/download`,
};
