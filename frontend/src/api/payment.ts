import api from './axios';
import { PaymentRequest, PaymentResponse } from '@/types';

export const paymentApi = {
  /**
   * 결제 생성
   * POST /payments
   */
  createPayment: async (request: PaymentRequest): Promise<PaymentResponse> => {
    const response = await api.post<PaymentResponse>('/payments', request);
    return response.data;
  },

  /**
   * 결제 승인 (Authorization)
   * PATCH /payments/{id}/authorize
   */
  authorizePayment: async (id: number): Promise<PaymentResponse> => {
    const response = await api.patch<PaymentResponse>(`/payments/${id}/authorize`);
    return response.data;
  },

  /**
   * 결제 확정 (Capture)
   * PATCH /payments/{id}/capture
   */
  capturePayment: async (id: number): Promise<PaymentResponse> => {
    const response = await api.patch<PaymentResponse>(`/payments/${id}/capture`);
    return response.data;
  },

  /**
   * 결제 조회
   * GET /payments/{id}
   */
  getPayment: async (id: number): Promise<PaymentResponse> => {
    const response = await api.get<PaymentResponse>(`/payments/${id}`);
    return response.data;
  },
};
