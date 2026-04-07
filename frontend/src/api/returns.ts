import api from './axios';
import { CreateReturnRequest, ReturnResponse } from '@/types';

export const returnsApi = {
  /**
   * 반품/교환 생성
   * POST /api/returns
   */
  createReturn: async (request: CreateReturnRequest): Promise<ReturnResponse> => {
    const response = await api.post<ReturnResponse>('/api/returns', request);
    return response.data;
  },

  /**
   * 반품/교환 단건 조회
   * GET /api/returns/{id}
   */
  getReturn: async (id: number): Promise<ReturnResponse> => {
    const response = await api.get<ReturnResponse>(`/api/returns/${id}`);
    return response.data;
  },

  /**
   * 주문별 반품/교환 조회
   * GET /api/returns/order/{orderId}
   */
  getReturnsByOrder: async (orderId: number): Promise<ReturnResponse[]> => {
    const response = await api.get<ReturnResponse[]>(`/api/returns/order/${orderId}`);
    return response.data;
  },

  /**
   * 사용자별 반품/교환 조회
   * GET /api/returns/user/{userId}
   */
  getReturnsByUser: async (userId: number): Promise<ReturnResponse[]> => {
    const response = await api.get<ReturnResponse[]>(`/api/returns/user/${userId}`);
    return response.data;
  },

  /**
   * 상태별 반품/교환 조회 (관리자)
   * GET /api/returns/status/{status}
   */
  getReturnsByStatus: async (status: string): Promise<ReturnResponse[]> => {
    const response = await api.get<ReturnResponse[]>(`/api/returns/status/${status}`);
    return response.data;
  },

  /**
   * 반품/교환 승인 (관리자)
   * PATCH /api/returns/{id}/approve
   */
  approveReturn: async (id: number): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/approve`);
    return response.data;
  },

  /**
   * 반품/교환 거절 (관리자)
   * PATCH /api/returns/{id}/reject
   */
  rejectReturn: async (id: number, reason: string): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/reject`, { reason });
    return response.data;
  },

  /**
   * 반품/교환 반송 발송 (사용자가 운송장 정보 입력)
   * PATCH /api/returns/{id}/ship
   */
  shipReturn: async (id: number, trackingNumber: string, carrier: string): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/ship`, {
      trackingNumber,
      carrier,
    });
    return response.data;
  },

  /**
   * 반품/교환 수령 확인 (관리자)
   * PATCH /api/returns/{id}/receive
   */
  receiveReturn: async (id: number): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/receive`);
    return response.data;
  },

  /**
   * 반품/교환 완료 (관리자)
   * PATCH /api/returns/{id}/complete
   */
  completeReturn: async (id: number): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/complete`);
    return response.data;
  },

  /**
   * 반품/교환 취소
   * PATCH /api/returns/{id}/cancel
   */
  cancelReturn: async (id: number): Promise<ReturnResponse> => {
    const response = await api.patch<ReturnResponse>(`/api/returns/${id}/cancel`);
    return response.data;
  },
};
