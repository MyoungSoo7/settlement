import api from './axios';
import {
  CategoryResponse,
  CreateCategoryRequest,
  UpdateCategoryRequest,
} from '@/types';

export const categoryApi = {
  /**
   * 카테고리 생성
   * POST /api/categories
   */
  createCategory: async (request: CreateCategoryRequest): Promise<CategoryResponse> => {
    const response = await api.post<CategoryResponse>('/api/categories', request);
    return response.data;
  },

  /**
   * 카테고리 조회
   * GET /api/categories/{id}
   */
  getCategory: async (id: number): Promise<CategoryResponse> => {
    const response = await api.get<CategoryResponse>(`/api/categories/${id}`);
    return response.data;
  },

  /**
   * 전체 카테고리 목록 조회
   * GET /api/categories
   */
  getAllCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories');
    return response.data;
  },

  /**
   * 활성 카테고리 목록 조회
   * GET /api/categories/active
   */
  getActiveCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories/active');
    return response.data;
  },

  /**
   * 최상위 카테고리 목록 조회
   * GET /api/categories/root
   */
  getRootCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories/root');
    return response.data;
  },

  /**
   * 하위 카테고리 목록 조회
   * GET /api/categories/parent/{parentId}
   */
  getSubCategories: async (parentId: number): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>(`/api/categories/parent/${parentId}`);
    return response.data;
  },

  /**
   * 카테고리 수정
   * PUT /api/categories/{id}
   */
  updateCategory: async (id: number, request: UpdateCategoryRequest): Promise<CategoryResponse> => {
    const response = await api.put<CategoryResponse>(`/api/categories/${id}`, request);
    return response.data;
  },

  /**
   * 카테고리 활성화
   * PATCH /api/categories/{id}/activate
   */
  activateCategory: async (id: number): Promise<void> => {
    await api.patch(`/api/categories/${id}/activate`);
  },

  /**
   * 카테고리 비활성화
   * PATCH /api/categories/{id}/deactivate
   */
  deactivateCategory: async (id: number): Promise<void> => {
    await api.patch(`/api/categories/${id}/deactivate`);
  },
};
