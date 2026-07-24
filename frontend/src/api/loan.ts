import api from './axios';

/** 선정산 대출 신청 요청 */
export interface LoanCreateRequest {
  sellerId: number;
  principal: number;       // 대출 원금 (> 0)
  financingDays: number;   // 선지급 일수 (>= 0)
}

export interface LoanResponse {
  id: number;
  sellerId: number;
  principal: number;
  fee: number;
  outstanding: number;
  status: string;              // LoanStatus enum 이름 (REQUESTED/APPROVED/DISBURSED/REPAID/REJECTED/OVERDUE/WRITTEN_OFF)
  disbursedAt?: string | null; // 실행 시각(ISO). 실행 전/구 데이터는 null
  dueAt?: string | null;       // 만기일(ISO) = 실행시각 + 선지급일수. 자동 연체/상각 기준
}

/** 기업대출 신용등급 (A 최상 ~ E 최하) */
export type CorporateCreditGrade = 'A' | 'B' | 'C' | 'D' | 'E';

/** 기업대출 상태 */
export type CorporateLoanStatus = 'REQUESTED' | 'APPROVED' | 'DISBURSED' | 'REPAID' | 'REJECTED';

/** 기업 신용평가 — GET /loans/corporate/credit/{stockCode} */
export interface CorporateCredit {
  stockCode: string;
  corpName: string;
  market: string;
  fiscalYear: number;
  creditScore: number;
  creditGrade: CorporateCreditGrade;
  limit: number;
  debtRatio: number | null;
  operatingMargin: number | null;
  roa: number | null;
  reputationGrade: string | null;
}

export interface CorporateLoanRequest {
  stockCode: string;
  principal: number;
  termDays: number;
}

/** 기업대출 */
export interface CorporateLoan {
  id: number;
  stockCode: string;
  corpName: string;
  principal: number;
  fee: number;
  outstanding: number;
  termDays: number;
  creditScore: number;
  creditGrade: CorporateCreditGrade;
  status: CorporateLoanStatus;
}

export const loanApi = {
  /** 대출 신청. POST /loans */
  request: async (req: LoanCreateRequest): Promise<LoanResponse> => {
    const res = await api.post<LoanResponse>('/loans', req);
    return res.data;
  },

  /** 대출 실행(선지급). POST /loans/{id}/disburse */
  disburse: async (id: number): Promise<LoanResponse> => {
    const res = await api.post<LoanResponse>(`/loans/${id}/disburse`);
    return res.data;
  },

  /** 셀러별 대출 목록. GET /loans?sellerId= */
  bySeller: async (sellerId: number): Promise<LoanResponse[]> => {
    const res = await api.get<LoanResponse[]>(`/loans?sellerId=${sellerId}`);
    return res.data;
  },

  /** 연체 진입(ADMIN 회수). POST /loans/{id}/overdue */
  markOverdue: async (id: number): Promise<LoanResponse> => {
    const res = await api.post<LoanResponse>(`/loans/${id}/overdue`);
    return res.data;
  },

  /** 상각(ADMIN 대손 확정). POST /loans/{id}/write-off */
  writeOff: async (id: number): Promise<LoanResponse> => {
    const res = await api.post<LoanResponse>(`/loans/${id}/write-off`);
    return res.data;
  },

  /** 기업 신용평가. GET /loans/corporate/credit/{stockCode} */
  corporateCredit: async (stockCode: string): Promise<CorporateCredit> => {
    const res = await api.get<CorporateCredit>(`/loans/corporate/credit/${stockCode}`);
    return res.data;
  },

  /** 기업대출 신청. POST /loans/corporate */
  requestCorporate: async (req: CorporateLoanRequest): Promise<CorporateLoan> => {
    const res = await api.post<CorporateLoan>('/loans/corporate', req);
    return res.data;
  },

  /** 기업대출 실행(선지급). POST /loans/corporate/{id}/disburse */
  disburseCorporate: async (id: number): Promise<CorporateLoan> => {
    const res = await api.post<CorporateLoan>(`/loans/corporate/${id}/disburse`);
    return res.data;
  },

  /** 기업대출 상환(미상환잔액 차감). POST /loans/corporate/{id}/repay */
  repayCorporate: async (id: number, amount: number): Promise<CorporateLoan> => {
    const res = await api.post<CorporateLoan>(`/loans/corporate/${id}/repay`, { amount });
    return res.data;
  },

  /** 종목별 기업대출 목록. GET /loans/corporate?stockCode= */
  corporateByStock: async (stockCode: string): Promise<CorporateLoan[]> => {
    const res = await api.get<CorporateLoan[]>(`/loans/corporate?stockCode=${stockCode}`);
    return res.data;
  },
};
