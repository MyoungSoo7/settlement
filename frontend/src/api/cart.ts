import api from './axios';
import {
  CartResponse,
  AddCartItemRequest,
  UpdateCartItemRequest,
} from '@/types';

export const cartApi = {
  /**
   * 장바구니 조회
   * GET /api/cart
   */
  getCart: async (userId: number): Promise<CartResponse> => {
    const response = await api.get<CartResponse>('/api/cart', {
      params: { userId },
    });
    return response.data;
  },

  /**
   * 장바구니 아이템 추가
   * POST /api/cart/items
   */
  addItem: async (userId: number, request: AddCartItemRequest): Promise<CartResponse> => {
    const response = await api.post<CartResponse>('/api/cart/items', request, {
      params: { userId },
    });
    return response.data;
  },

  /**
   * 장바구니 아이템 수량 변경
   * PATCH /api/cart/items/{productId}
   */
  updateQuantity: async (userId: number, productId: number, request: UpdateCartItemRequest): Promise<CartResponse> => {
    const response = await api.patch<CartResponse>(`/api/cart/items/${productId}`, request, {
      params: { userId },
    });
    return response.data;
  },

  /**
   * 장바구니 아이템 제거
   * DELETE /api/cart/items/{productId}
   */
  removeItem: async (userId: number, productId: number): Promise<CartResponse> => {
    const response = await api.delete<CartResponse>(`/api/cart/items/${productId}`, {
      params: { userId },
    });
    return response.data;
  },

  /**
   * 장바구니 비우기
   * DELETE /api/cart
   */
  clearCart: async (userId: number): Promise<CartResponse> => {
    const response = await api.delete<CartResponse>('/api/cart', {
      params: { userId },
    });
    return response.data;
  },
};
