import api from './axios';
import { RefundRequest, RefundResponse, PaymentResponse } from '@/types';

export const refundApi = {
  /**
   * 환불 요청 (부분/전체 환불 통합)
   * POST /refunds/{paymentId}
   * Headers: Idempotency-Key (필수)
   */
  createRefund: async (
    paymentId: number,
    request: RefundRequest,
    idempotencyKey: string
  ): Promise<RefundResponse> => {
    const response = await api.post<RefundResponse>(`/refunds/${paymentId}`, request, {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    });
    return response.data;
  },

  /**
   * 전체 환불
   * POST /refunds/full/{paymentId}
   */
  processFullRefund: async (paymentId: number, idempotencyKey: string): Promise<PaymentResponse> => {
    const response = await api.post<PaymentResponse>(`/refunds/full/${paymentId}`, null, {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    });
    return response.data;
  },

  /**
   * 부분 환불
   * POST /refunds/partial/{paymentId}
   */
  processPartialRefund: async (
    paymentId: number,
    refundAmount: number,
    idempotencyKey: string
  ): Promise<PaymentResponse> => {
    const response = await api.post<PaymentResponse>(
      `/refunds/partial/${paymentId}`,
      null,
      {
        params: { refundAmount },
        headers: {
          'Idempotency-Key': idempotencyKey,
        },
      }
    );
    return response.data;
  },
};
