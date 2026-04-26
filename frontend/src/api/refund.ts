import api from './axios';
import { RefundRequest, RefundResponse } from '@/types';

export const refundApi = {
  /**
   * 환불 요청 (부분/전체 환불 통합)
   * POST /api/refunds/{paymentId}
   * Headers: Idempotency-Key (필수)
   */
  createRefund: async (
    paymentId: number,
    request: RefundRequest,
    idempotencyKey: string
  ): Promise<RefundResponse> => {
    const response = await api.post<RefundResponse>(`/api/refunds/${paymentId}`, request, {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    });
    return response.data;
  },
};
