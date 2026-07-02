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
  status: string;          // LoanStatus enum 이름
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
};
