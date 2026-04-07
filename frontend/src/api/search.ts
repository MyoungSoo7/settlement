import api from './axios';

export const searchApi = {
  search: async (keyword: string, page = 0, size = 20) => {
    const response = await api.get('/api/products/search', { params: { keyword, page, size } });
    return response.data;
  },
  searchWithFilters: async (params: {
    keyword?: string;
    categoryId?: number;
    minPrice?: number;
    maxPrice?: number;
    status?: string;
    page?: number;
    size?: number;
  }) => {
    const response = await api.get('/api/products/search/filter', { params });
    return response.data;
  },
  suggest: async (prefix: string, size = 5) => {
    const response = await api.get('/api/products/search/suggest', { params: { prefix, size } });
    return response.data;
  },
  reindexAll: async () => {
    const response = await api.post('/api/products/search/reindex');
    return response.data;
  },
  indexProduct: async (productId: number) => {
    const response = await api.post(`/api/products/search/${productId}/index`);
    return response.data;
  },
};
