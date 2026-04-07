import api from './axios';
import { OrderCreateRequest, OrderResponse, CreateMultiItemOrderRequest, MultiItemOrderResponse } from '@/types';

export const orderApi = {
  /**
   * 주문 생성
   * POST /api/orders
   */
  createOrder: async (request: OrderCreateRequest): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>('/api/orders', request);
    return response.data;
  },

  /**
   * 주문 조회
   * GET /api/orders/{id}
   */
  getOrder: async (id: number): Promise<OrderResponse> => {
    const response = await api.get<OrderResponse>(`/api/orders/${id}`);
    return response.data;
  },

  /**
   * 사용자별 주문 목록 조회
   * GET /api/orders/user/{userId}
   */
  getUserOrders: async (userId: number): Promise<OrderResponse[]> => {
    const response = await api.get<OrderResponse[]>(`/api/orders/user/${userId}`);
    return response.data;
  },

  /**
   * 주문 취소
   * PATCH /api/orders/{id}/cancel
   */
  cancelOrder: async (id: number): Promise<OrderResponse> => {
    const response = await api.patch<OrderResponse>(`/api/orders/${id}/cancel`);
    return response.data;
  },

  /**
   * 복수 상품 주문 생성
   * POST /api/orders/multi
   */
  createMultiItemOrder: async (request: CreateMultiItemOrderRequest): Promise<MultiItemOrderResponse> => {
    const response = await api.post<MultiItemOrderResponse>('/api/orders/multi', request);
    return response.data;
  },
};
