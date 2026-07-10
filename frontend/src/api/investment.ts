import api from './axios';

/** 투자 등급 (AAA 최상 ~ CCC 최하) */
export type InvestmentGrade = 'AAA' | 'AA' | 'A' | 'BBB' | 'BB' | 'B' | 'CCC';

/** 투자 주문 상태 */
export type InvestmentOrderStatus = 'REQUESTED' | 'APPROVED' | 'EXECUTED' | 'REJECTED' | 'CANCELED';

/** 축별 점수 — 비율 지표는 계산 불가 시 null */
export interface InvestmentAxisScore {
  score: number;
  maxScore: number;
  operatingMargin?: number | null;
  roa?: number | null;
  debtRatio?: number | null;
  equityRatio?: number | null;
  revenueGrowth?: number | null;
  netIncomeGrowth?: number | null;
}

/** 투자 점수 카드 — GET /api/investment/scores/{stockCode} */
export interface InvestmentScore {
  stockCode: string;
  companyName: string;
  market: string;
  fiscalYear: number;
  totalScore: number;      // 0~100
  grade: InvestmentGrade;
  investable: boolean;
  profitability: InvestmentAxisScore;
  stability: InvestmentAxisScore;
  growth: InvestmentAxisScore;
}

/** 투자 주문 */
export interface InvestmentOrder {
  id: number;
  sellerId: number;
  stockCode: string;
  amount: number;
  scoreAtOrder: number;
  gradeAtOrder: InvestmentGrade;
  status: InvestmentOrderStatus;
}

/** 투자 재원 현황 — GET /api/investment/funding/{sellerId} */
export interface InvestmentFunding {
  sellerId: number;
  confirmedTotal: number;
  investedTotal: number;
  available: number;
}

export interface InvestmentOrderRequest {
  sellerId: number;
  stockCode: string;
  amount: number;
}

export const investmentApi = {
  /** 종목 투자 점수 조회. GET /api/investment/scores/{stockCode} */
  score: async (stockCode: string): Promise<InvestmentScore> => {
    const res = await api.get<InvestmentScore>(`/api/investment/scores/${stockCode}`);
    return res.data;
  },

  /** 투자 재원 조회. GET /api/investment/funding/{sellerId} */
  funding: async (sellerId: number): Promise<InvestmentFunding> => {
    const res = await api.get<InvestmentFunding>(`/api/investment/funding/${sellerId}`);
    return res.data;
  },

  /** 투자 주문 생성. POST /api/investment/orders (422=투자부적격/재원부족) */
  createOrder: async (req: InvestmentOrderRequest): Promise<InvestmentOrder> => {
    const res = await api.post<InvestmentOrder>('/api/investment/orders', req);
    return res.data;
  },

  /** 투자 주문 집행. POST /api/investment/orders/{id}/execute */
  execute: async (id: number): Promise<InvestmentOrder> => {
    const res = await api.post<InvestmentOrder>(`/api/investment/orders/${id}/execute`);
    return res.data;
  },

  /** 투자 주문 취소. POST /api/investment/orders/{id}/cancel */
  cancel: async (id: number): Promise<InvestmentOrder> => {
    const res = await api.post<InvestmentOrder>(`/api/investment/orders/${id}/cancel`);
    return res.data;
  },

  /** 셀러별 투자 주문 목록. GET /api/investment/orders?sellerId= */
  ordersBySeller: async (sellerId: number): Promise<InvestmentOrder[]> => {
    const res = await api.get<InvestmentOrder[]>(`/api/investment/orders?sellerId=${sellerId}`);
    return res.data;
  },
};
