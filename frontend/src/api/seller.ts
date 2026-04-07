import api from './axios';
import { RegisterSellerRequest, SellerResponse, UpdateBankInfoRequest } from '@/types';

export const sellerApi = {
  /**
   * 판매자 등록
   * POST /api/sellers
   */
  register: async (request: RegisterSellerRequest): Promise<SellerResponse> => {
    const response = await api.post<SellerResponse>('/api/sellers', request);
    return response.data;
  },

  /**
   * 판매자 조회
   * GET /api/sellers/{id}
   */
  getById: async (id: number): Promise<SellerResponse> => {
    const response = await api.get<SellerResponse>(`/api/sellers/${id}`);
    return response.data;
  },

  /**
   * 사용자별 판매자 조회
   * GET /api/sellers/user/{userId}
   */
  getByUserId: async (userId: number): Promise<SellerResponse> => {
    const response = await api.get<SellerResponse>(`/api/sellers/user/${userId}`);
    return response.data;
  },

  /**
   * 전체 판매자 목록 (관리자)
   * GET /api/sellers
   */
  getAll: async (): Promise<SellerResponse[]> => {
    const response = await api.get<SellerResponse[]>('/api/sellers');
    return response.data;
  },

  /**
   * 상태별 판매자 목록 (관리자)
   * GET /api/sellers/status/{status}
   */
  getByStatus: async (status: string): Promise<SellerResponse[]> => {
    const response = await api.get<SellerResponse[]>(`/api/sellers/status/${status}`);
    return response.data;
  },

  /**
   * 판매자 승인 (관리자)
   * PATCH /api/sellers/{id}/approve
   */
  approve: async (id: number): Promise<SellerResponse> => {
    const response = await api.patch<SellerResponse>(`/api/sellers/${id}/approve`);
    return response.data;
  },

  /**
   * 판매자 거부 (관리자)
   * PATCH /api/sellers/{id}/reject
   */
  reject: async (id: number): Promise<SellerResponse> => {
    const response = await api.patch<SellerResponse>(`/api/sellers/${id}/reject`);
    return response.data;
  },

  /**
   * 판매자 정지 (관리자)
   * PATCH /api/sellers/{id}/suspend
   */
  suspend: async (id: number): Promise<SellerResponse> => {
    const response = await api.patch<SellerResponse>(`/api/sellers/${id}/suspend`);
    return response.data;
  },

  /**
   * 판매자 재활성화 (관리자)
   * PATCH /api/sellers/{id}/reactivate
   */
  reactivate: async (id: number): Promise<SellerResponse> => {
    const response = await api.patch<SellerResponse>(`/api/sellers/${id}/reactivate`);
    return response.data;
  },

  /**
   * 계좌 정보 수정
   * PUT /api/sellers/{id}/bank-info
   */
  updateBankInfo: async (id: number, request: UpdateBankInfoRequest): Promise<SellerResponse> => {
    const response = await api.put<SellerResponse>(`/api/sellers/${id}/bank-info`, request);
    return response.data;
  },

  /**
   * 수수료율 변경 (관리자)
   * PATCH /api/sellers/{id}/commission-rate?rate=
   */
  updateCommissionRate: async (id: number, rate: number): Promise<SellerResponse> => {
    const response = await api.patch<SellerResponse>(`/api/sellers/${id}/commission-rate`, null, {
      params: { rate },
    });
    return response.data;
  },
};
