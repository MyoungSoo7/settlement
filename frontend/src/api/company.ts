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

/** 국민연금 사업장 목록 1행 — 전국 사업장(상장사 stockCode 체계와 무관한 독립 검색) */
export interface Workforce {
  workplaceName: string;
  bizRegNoPrefix: string;          // 사업자등록번호 앞 6자리 (상세 복합키 요소)
  industryName: string | null;
  address: string | null;
  headcount: number;
  estimatedAnnualSalary: number | null;   // 가입자수 0이면 null
  snapshotMonth: string;           // YYYY-MM (상세 복합키 요소)
  note: string;
}

export interface WorkforcePage {
  content: Workforce[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 비교 단계 — EXACT(동일 집단) / BROADENED(표본 10 미만이라 업종 앞3자리·시도로 한 단계 확대) */
export type ComparisonLevel = 'EXACT' | 'BROADENED';

export type ComparisonUnavailableReason =
  | 'SAMPLE_TOO_SMALL'        // 확대해도 표본 10 미만
  | 'INDUSTRY_CODE_MISSING'   // 원본 업종코드 공란 (실데이터에 실존)
  | 'REGION_UNPARSEABLE';     // 주소에서 시도 파싱 실패

/** 인원수 지표 — 금액이 아니라 수치 그대로 */
export interface HeadcountMetric {
  median: number;
  difference: number;
  differenceRate: number | null;   // 집단 중앙값 0이면 null
  percentile: number;              // cume_dist ×100 — 집단에서 이 값 이하인 사업장 비율(%)
}

/** 금액 지표 — median·difference 는 정밀도 보존을 위한 소수 문자열(백엔드 계약) */
export interface MoneyMetric {
  median: string;
  difference: string;
  differenceRate: number | null;
  percentile: number;
}

/** 한 비교축(업종/지역)의 판정 — 비교 불가면 unavailableReason 이 채워지고 지표는 null */
export interface WorkforceGroupComparison {
  comparisonLevel: ComparisonLevel | null;
  groupKey: string | null;
  sampleSize: number;
  unavailableReason: ComparisonUnavailableReason | null;
  headcount: HeadcountMetric | null;
  estimatedAnnualSalary: MoneyMetric | null;
}

/** 사업장 단건 상세 + 업종·지역 집단 비교 — 최상위 금액 필드도 소수 문자열 */
export interface WorkforceComparison {
  workplaceName: string;
  bizRegNoPrefix: string;
  snapshotMonth: string;
  industryCode: string | null;
  industryName: string | null;
  address: string | null;
  sido: string | null;
  sigungu: string | null;
  headcount: number;
  estimatedAnnualSalary: string | null;
  salaryCapReached: boolean;              // 기준소득월액 상한 도달 → 백분위 신뢰도 경고
  salaryCapMonthlyAmount: string | null;  // 고시표 범위 밖 기준월이면 null
  industryComparison: WorkforceGroupComparison;
  regionComparison: WorkforceGroupComparison;
  note: string;
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

  /** 국민연금 사업장 목록/검색. GET /api/company/workforce */
  workforce: async (name: string, page: number, size = 20): Promise<WorkforcePage> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (name.trim()) params.set('name', name.trim());
    const res = await api.get<WorkforcePage>(`/api/company/workforce?${params}`);
    return res.data;
  },

  /**
   * 사업장 단건 상세 + 업종·지역 비교. GET /api/company/workforce/detail
   * 식별자는 복합키(사업장명 + 사업자번호 앞6 + 기준월) — 실데이터에 따옴표·느낌표가 든
   * 사업장명이 있어 path 가 아닌 query parameter 계약이다. URLSearchParams 가 인코딩을 책임진다.
   */
  workforceDetail: async (
    name: string,
    bizRegNoPrefix: string,
    snapshotMonth: string,
  ): Promise<WorkforceComparison> => {
    const params = new URLSearchParams({ name, bizRegNoPrefix, snapshotMonth });
    const res = await api.get<WorkforceComparison>(`/api/company/workforce/detail?${params}`);
    return res.data;
  },
};
