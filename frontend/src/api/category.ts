import api from './axios';
import {
  CategoryResponse,
  CreateCategoryRequest,
  UpdateCategoryRequest,
} from '@/types';

export const categoryApi = {
  /**
   * 카테고리 생성
   */
  createCategory: async (request: CreateCategoryRequest): Promise<CategoryResponse> => {
    const response = await api.post<CategoryResponse>('/api/categories', request);
    return response.data;
  },

  /**
   * 카테고리 조회
   */
  getCategory: async (id: number): Promise<CategoryResponse> => {
    const response = await api.get<CategoryResponse>(`/api/categories/${id}`);
    return response.data;
  },

  /**
   * 전체 카테고리 목록 조회
   */
  getAllCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories');
    return response.data;
  },

  /**
   * 활성 카테고리 목록 조회
   */
  getActiveCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories/active');
    return response.data;
  },

  /**
   * 최상위 카테고리 목록 조회
   */
  getRootCategories: async (): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>('/api/categories/root');
    return response.data;
  },

  /**
   * 하위 카테고리 목록 조회
   */
  getSubCategories: async (parentId: number): Promise<CategoryResponse[]> => {
    const response = await api.get<CategoryResponse[]>(`/api/categories/parent/${parentId}`);
    return response.data;
  },

  /**
   * 카테고리 수정
   */
  updateCategory: async (id: number, request: UpdateCategoryRequest): Promise<CategoryResponse> => {
    const response = await api.put<CategoryResponse>(`/api/categories/${id}`, request);
    return response.data;
  },

  /**
   * 카테고리 활성화
   */
  activateCategory: async (id: number): Promise<void> => {
    await api.post(`/api/categories/${id}/activate`);
  },

  /**
   * 카테고리 비활성화
   */
  deactivateCategory: async (id: number): Promise<void> => {
    await api.post(`/api/categories/${id}/deactivate`);
  },
};
