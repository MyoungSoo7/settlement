import api from './axios';
import { CouponResponse, CouponValidateResponse, CouponCreateRequest } from '@/types';

export const couponApi = {
  /**
   * 쿠폰 유효성 검증
   * GET /api/coupons/{code}/validate?userId=&amount=
   */
  validate: async (code: string, userId: number, amount: number): Promise<CouponValidateResponse> => {
    const response = await api.get<CouponValidateResponse>(`/api/coupons/${code}/validate`, {
      params: { userId, amount },
    });
    return response.data;
  },

  /**
   * 쿠폰 사용 처리
   * POST /api/coupons/{code}/usage
   */
  use: async (code: string, userId: number, orderId: number): Promise<void> => {
    await api.post(`/api/coupons/${code}/usage`, { userId, orderId });
  },

  /**
   * 전체 쿠폰 목록 (관리자)
   * GET /api/coupons
   */
  getAll: async (): Promise<CouponResponse[]> => {
    const response = await api.get<CouponResponse[]>('/api/coupons');
    return response.data;
  },

  /**
   * 쿠폰 생성 (관리자)
   * POST /api/coupons
   */
  create: async (data: CouponCreateRequest): Promise<CouponResponse> => {
    const response = await api.post<CouponResponse>('/api/coupons', data);
    return response.data;
  },
};
