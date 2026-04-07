import api from './axios';
import {
  PointResponse,
  PointTransactionResponse,
  EarnPointsRequest,
  UsePointsRequest,
} from '@/types';

export const pointApi = {
  /**
   * 포인트 잔액 조회
   * GET /api/points/{userId}
   */
  getBalance: async (userId: number): Promise<PointResponse> => {
    const response = await api.get<PointResponse>(`/api/points/${userId}`);
    return response.data;
  },

  /**
   * 포인트 거래 내역 조회
   * GET /api/points/{userId}/transactions
   */
  getTransactions: async (userId: number): Promise<PointTransactionResponse[]> => {
    const response = await api.get<PointTransactionResponse[]>(`/api/points/${userId}/transactions`);
    return response.data;
  },

  /**
   * 포인트 적립
   * POST /api/points/earn
   */
  earn: async (data: EarnPointsRequest): Promise<PointTransactionResponse> => {
    const response = await api.post<PointTransactionResponse>('/api/points/earn', data);
    return response.data;
  },

  /**
   * 포인트 사용
   * POST /api/points/use
   */
  use: async (data: UsePointsRequest): Promise<PointTransactionResponse> => {
    const response = await api.post<PointTransactionResponse>('/api/points/use', data);
    return response.data;
  },

  /**
   * 포인트 적립 취소
   * POST /api/points/cancel-earn
   */
  cancelEarn: async (data: {
    userId: number;
    amount: number;
    description: string;
    referenceType?: string;
    referenceId?: number;
  }): Promise<PointTransactionResponse> => {
    const response = await api.post<PointTransactionResponse>('/api/points/cancel-earn', data);
    return response.data;
  },

  /**
   * 포인트 사용 취소
   * POST /api/points/cancel-use
   */
  cancelUse: async (data: {
    userId: number;
    amount: number;
    description: string;
    referenceType?: string;
    referenceId?: number;
  }): Promise<PointTransactionResponse> => {
    const response = await api.post<PointTransactionResponse>('/api/points/cancel-use', data);
    return response.data;
  },

  /**
   * 관리자 포인트 조정
   * POST /api/points/admin/adjust
   */
  adminAdjust: async (data: {
    userId: number;
    amount: number;
    description: string;
  }): Promise<PointTransactionResponse> => {
    const response = await api.post<PointTransactionResponse>('/api/points/admin/adjust', data);
    return response.data;
  },
};
