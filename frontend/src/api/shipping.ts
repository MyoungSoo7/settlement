import api from './axios';
import {
  ShippingAddressResponse,
  CreateShippingAddressRequest,
  DeliveryResponse,
  DeliveryStatus,
} from '@/types';

export const shippingApi = {
  /**
   * 배송지 생성
   * POST /api/shipping-addresses
   */
  createAddress: async (request: CreateShippingAddressRequest): Promise<ShippingAddressResponse> => {
    const response = await api.post<ShippingAddressResponse>('/api/shipping-addresses', request);
    return response.data;
  },

  /**
   * 사용자 배송지 목록
   * GET /api/shipping-addresses/user/{userId}
   */
  getUserAddresses: async (userId: number): Promise<ShippingAddressResponse[]> => {
    const response = await api.get<ShippingAddressResponse[]>(`/api/shipping-addresses/user/${userId}`);
    return response.data;
  },

  /**
   * 배송지 수정
   * PUT /api/shipping-addresses/{id}
   */
  updateAddress: async (id: number, request: {
    recipientName: string;
    phone: string;
    zipCode: string;
    address: string;
    addressDetail?: string;
  }): Promise<ShippingAddressResponse> => {
    const response = await api.put<ShippingAddressResponse>(`/api/shipping-addresses/${id}`, request);
    return response.data;
  },

  /**
   * 배송지 삭제
   * DELETE /api/shipping-addresses/{id}
   */
  deleteAddress: async (id: number): Promise<void> => {
    await api.delete(`/api/shipping-addresses/${id}`);
  },

  /**
   * 기본 배송지 설정
   * PATCH /api/shipping-addresses/{id}/default?userId=
   */
  setDefaultAddress: async (id: number, userId: number): Promise<void> => {
    await api.patch(`/api/shipping-addresses/${id}/default`, null, { params: { userId } });
  },

  /**
   * 배송 생성
   * POST /api/deliveries
   */
  createDelivery: async (request: {
    orderId: number;
    addressId?: number;
    recipientName: string;
    phone: string;
    address: string;
    shippingFee: number;
  }): Promise<DeliveryResponse> => {
    const response = await api.post<DeliveryResponse>('/api/deliveries', request);
    return response.data;
  },

  /**
   * 배송 조회
   * GET /api/deliveries/{id}
   */
  getDelivery: async (id: number): Promise<DeliveryResponse> => {
    const response = await api.get<DeliveryResponse>(`/api/deliveries/${id}`);
    return response.data;
  },

  /**
   * 주문별 배송 조회
   * GET /api/deliveries/order/{orderId}
   */
  getDeliveryByOrderId: async (orderId: number): Promise<DeliveryResponse> => {
    const response = await api.get<DeliveryResponse>(`/api/deliveries/order/${orderId}`);
    return response.data;
  },

  /**
   * 배송 출발 처리
   * PATCH /api/deliveries/{id}/ship
   */
  shipDelivery: async (id: number, request: {
    trackingNumber: string;
    carrier: string;
  }): Promise<DeliveryResponse> => {
    const response = await api.patch<DeliveryResponse>(`/api/deliveries/${id}/ship`, request);
    return response.data;
  },

  /**
   * 배송 상태 변경
   * PATCH /api/deliveries/{id}/status
   */
  updateDeliveryStatus: async (id: number, status: DeliveryStatus): Promise<DeliveryResponse> => {
    const response = await api.patch<DeliveryResponse>(`/api/deliveries/${id}/status`, { status });
    return response.data;
  },

  /**
   * 상태별 배송 목록 (관리자)
   * GET /api/deliveries/status/{status}
   */
  getDeliveriesByStatus: async (status: DeliveryStatus): Promise<DeliveryResponse[]> => {
    const response = await api.get<DeliveryResponse[]>(`/api/deliveries/status/${status}`);
    return response.data;
  },
};
