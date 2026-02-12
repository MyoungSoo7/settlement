import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';

// Axios 인스턴스 생성
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request Interceptor: JWT 토큰 자동 추가
api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('access_token');

    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

// Response Interceptor: 에러 처리 및 자동 로그아웃
api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error: AxiosError) => {
    // 401 Unauthorized: 토큰 만료 또는 인증 실패
    if (error.response?.status === 401) {
      localStorage.removeItem('access_token');
      localStorage.removeItem('user_email');
      localStorage.removeItem('user_role');

      // 로그인 페이지로 리다이렉트
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }

    // 403 Forbidden: 권한 없음
    if (error.response?.status === 403) {
      console.error('Access denied:', error.response.data);
    }

    return Promise.reject(error);
  }
);

export default api;
