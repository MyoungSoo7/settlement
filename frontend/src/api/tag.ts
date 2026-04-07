import api from './axios';
import {
  TagResponse,
  CreateTagRequest,
  UpdateTagRequest,
} from '@/types';

export const tagApi = {
  /**
   * 태그 생성
   * POST /api/tags
   */
  createTag: async (request: CreateTagRequest): Promise<TagResponse> => {
    const response = await api.post<TagResponse>('/api/tags', request);
    return response.data;
  },

  /**
   * 태그 조회
   * GET /api/tags/{id}
   */
  getTag: async (id: number): Promise<TagResponse> => {
    const response = await api.get<TagResponse>(`/api/tags/${id}`);
    return response.data;
  },

  /**
   * 전체 태그 목록 조회
   * GET /api/tags
   */
  getAllTags: async (): Promise<TagResponse[]> => {
    const response = await api.get<TagResponse[]>('/api/tags');
    return response.data;
  },

  /**
   * 상품별 태그 목록 조회
   * GET /api/tags/product/{productId}
   */
  getTagsByProduct: async (productId: number): Promise<TagResponse[]> => {
    const response = await api.get<TagResponse[]>(`/api/tags/product/${productId}`);
    return response.data;
  },

  /**
   * 태그 수정
   * PUT /api/tags/{id}
   */
  updateTag: async (id: number, request: UpdateTagRequest): Promise<TagResponse> => {
    const response = await api.put<TagResponse>(`/api/tags/${id}`, request);
    return response.data;
  },

  /**
   * 태그 삭제
   * DELETE /api/tags/{id}
   */
  deleteTag: async (id: number): Promise<void> => {
    await api.delete(`/api/tags/${id}`);
  },

  /**
   * 상품에 태그 추가
   * PUT /api/tags/products/{productId}/tags/{tagId}
   */
  addTagToProduct: async (productId: number, tagId: number): Promise<void> => {
    await api.put(`/api/tags/products/${productId}/tags/${tagId}`);
  },

  /**
   * 상품에서 태그 제거
   * DELETE /api/tags/products/{productId}/tags/{tagId}
   */
  removeTagFromProduct: async (productId: number, tagId: number): Promise<void> => {
    await api.delete(`/api/tags/products/${productId}/tags/${tagId}`);
  },
};
