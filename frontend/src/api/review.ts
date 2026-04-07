import api from './axios';
import { ReviewCreateRequest, ReviewUpdateRequest, ReviewResponse } from '@/types';

export const reviewApi = {
  /**
   * 리뷰 작성
   * POST /api/reviews
   */
  createReview: async (request: ReviewCreateRequest): Promise<ReviewResponse> => {
    const response = await api.post<ReviewResponse>('/api/reviews', request);
    return response.data;
  },

  /**
   * 상품 리뷰 목록
   * GET /api/reviews/product/{productId}
   */
  getProductReviews: async (productId: number): Promise<ReviewResponse[]> => {
    const response = await api.get<ReviewResponse[]>(`/api/reviews/product/${productId}`);
    return response.data;
  },

  /**
   * 사용자 리뷰 목록
   * GET /api/reviews/user/{userId}
   */
  getUserReviews: async (userId: number): Promise<ReviewResponse[]> => {
    const response = await api.get<ReviewResponse[]>(`/api/reviews/user/${userId}`);
    return response.data;
  },

  /**
   * 리뷰 수정
   * PATCH /api/reviews/{id}
   */
  updateReview: async (id: number, request: ReviewUpdateRequest): Promise<ReviewResponse> => {
    const response = await api.patch<ReviewResponse>(`/api/reviews/${id}`, request);
    return response.data;
  },

  /**
   * 리뷰 삭제
   * DELETE /api/reviews/{id}?userId=
   */
  deleteReview: async (id: number, userId: number): Promise<void> => {
    await api.delete(`/api/reviews/${id}`, { params: { userId } });
  },
};
