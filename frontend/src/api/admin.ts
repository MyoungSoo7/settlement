import api from './axios';
import { OrderResponse } from '@/types';

export interface AdminUserResponse {
  id: number;
  email: string;
  role: string;
  createdAt: string;
}

export const adminApi = {
  /**
   * 전체 주문 목록 (관리자)
   * GET /api/orders/admin/all
   */
  getAllOrders: async (): Promise<OrderResponse[]> => {
    const response = await api.get<OrderResponse[]>('/api/orders/admin/all');
    return response.data;
  },

  /**
   * 전체 사용자 목록 (관리자)
   * GET /api/users/admin/all
   */
  getAllUsers: async (): Promise<AdminUserResponse[]> => {
    const response = await api.get<AdminUserResponse[]>('/api/users/admin/all');
    return response.data;
  },
};
