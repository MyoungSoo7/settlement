import api from './axios';
import {
  ProductOptionResponse,
  ProductVariantResponse,
  CreateProductOptionRequest,
  CreateProductVariantRequest,
} from '@/types';

export const productVariantApi = {
  /**
   * 상품 옵션 생성
   * POST /api/products/{productId}/variants/options
   */
  createOption: async (productId: number, request: CreateProductOptionRequest): Promise<ProductOptionResponse> => {
    const response = await api.post<ProductOptionResponse>(`/api/products/${productId}/variants/options`, request);
    return response.data;
  },

  /**
   * 상품 옵션 목록 조회
   * GET /api/products/{productId}/variants/options
   */
  getOptions: async (productId: number): Promise<ProductOptionResponse[]> => {
    const response = await api.get<ProductOptionResponse[]>(`/api/products/${productId}/variants/options`);
    return response.data;
  },

  /**
   * 상품 옵션 삭제
   * DELETE /api/products/{productId}/variants/options/{optionId}
   */
  deleteOption: async (productId: number, optionId: number): Promise<void> => {
    await api.delete(`/api/products/${productId}/variants/options/${optionId}`);
  },

  /**
   * 상품 변형 생성
   * POST /api/products/{productId}/variants
   */
  createVariant: async (productId: number, request: CreateProductVariantRequest): Promise<ProductVariantResponse> => {
    const response = await api.post<ProductVariantResponse>(`/api/products/${productId}/variants`, request);
    return response.data;
  },

  /**
   * 상품 변형 목록 조회
   * GET /api/products/{productId}/variants
   */
  getVariants: async (productId: number): Promise<ProductVariantResponse[]> => {
    const response = await api.get<ProductVariantResponse[]>(`/api/products/${productId}/variants`);
    return response.data;
  },

  /**
   * 상품 변형 단건 조회
   * GET /api/products/{productId}/variants/{variantId}
   */
  getVariant: async (productId: number, variantId: number): Promise<ProductVariantResponse> => {
    const response = await api.get<ProductVariantResponse>(`/api/products/${productId}/variants/${variantId}`);
    return response.data;
  },

  /**
   * 상품 변형 가격 수정
   * PATCH /api/products/{productId}/variants/{variantId}/price
   */
  updateVariantPrice: async (productId: number, variantId: number, price: number): Promise<ProductVariantResponse> => {
    const response = await api.patch<ProductVariantResponse>(`/api/products/${productId}/variants/${variantId}/price`, { price });
    return response.data;
  },

  /**
   * 상품 변형 재고 수정
   * PATCH /api/products/{productId}/variants/{variantId}/stock
   */
  updateVariantStock: async (productId: number, variantId: number, stockQuantity: number): Promise<ProductVariantResponse> => {
    const response = await api.patch<ProductVariantResponse>(`/api/products/${productId}/variants/${variantId}/stock`, { stockQuantity });
    return response.data;
  },

  /**
   * 상품 변형 비활성화
   * PATCH /api/products/{productId}/variants/{variantId}/deactivate
   */
  deactivateVariant: async (productId: number, variantId: number): Promise<void> => {
    await api.patch(`/api/products/${productId}/variants/${variantId}/deactivate`);
  },
};
