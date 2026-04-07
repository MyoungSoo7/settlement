import api from './axios';
import { WishlistItemResponse } from '@/types';

export const wishlistApi = {
  /**
   * 위시리스트 추가
   * POST /api/wishlist/{userId}/products/{productId}
   */
  addItem: async (userId: number, productId: number): Promise<WishlistItemResponse> => {
    const response = await api.post<WishlistItemResponse>(`/api/wishlist/${userId}/products/${productId}`);
    return response.data;
  },

  /**
   * 위시리스트 삭제
   * DELETE /api/wishlist/{userId}/products/{productId}
   */
  removeItem: async (userId: number, productId: number): Promise<void> => {
    await api.delete(`/api/wishlist/${userId}/products/${productId}`);
  },

  /**
   * 위시리스트 목록
   * GET /api/wishlist/{userId}
   */
  getUserWishlist: async (userId: number): Promise<WishlistItemResponse[]> => {
    const response = await api.get<WishlistItemResponse[]>(`/api/wishlist/${userId}`);
    return response.data;
  },

  /**
   * 위시리스트 존재 여부
   * GET /api/wishlist/{userId}/products/{productId}/exists
   */
  isInWishlist: async (userId: number, productId: number): Promise<boolean> => {
    const response = await api.get<boolean>(`/api/wishlist/${userId}/products/${productId}/exists`);
    return response.data;
  },

  /**
   * 위시리스트 수량
   * GET /api/wishlist/{userId}/count
   */
  getWishlistCount: async (userId: number): Promise<number> => {
    const response = await api.get<number>(`/api/wishlist/${userId}/count`);
    return response.data;
  },
};
