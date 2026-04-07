import api from './axios';
import { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from '@/types';

export const authApi = {
  /**
   * 로그인
   * POST /api/auth/login
   */
  login: async (credentials: LoginRequest): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/api/auth/login', credentials);
    return response.data;
  },

  /**
   * 회원가입
   * POST /api/users
   */
  register: async (userData: RegisterRequest): Promise<UserResponse> => {
    const response = await api.post<UserResponse>('/api/users', userData);
    return response.data;
  },

  /**
   * 로그아웃 (클라이언트 측)
   */
  logout: (): void => {
    localStorage.removeItem('access_token');
    localStorage.removeItem('user_email');
    localStorage.removeItem('user_role');
    localStorage.removeItem('login_timestamp');
  },

  /**
   * 토큰 저장
   */
  saveToken: (loginResponse: LoginResponse): void => {
    // 기존 로그인 세션이 있는지 확인
    const existingEmail = localStorage.getItem('user_email');

    if (existingEmail && existingEmail !== loginResponse.email) {
      console.warn(`세션 교체: ${existingEmail} -> ${loginResponse.email}`);
      // 기존 세션 정보 제거
      authApi.logout();
    }

    // 새 세션 저장
    localStorage.setItem('access_token', loginResponse.token);
    localStorage.setItem('user_email', loginResponse.email);
    localStorage.setItem('user_role', loginResponse.role);
    localStorage.setItem('login_timestamp', new Date().toISOString());
  },

  /**
   * 현재 사용자 정보 가져오기
   */
  getCurrentUser: (): { email: string; role: string } | null => {
    const email = localStorage.getItem('user_email');
    const role = localStorage.getItem('user_role');

    if (email && role) {
      return { email, role };
    }
    return null;
  },

  /**
   * 인증 여부 확인
   */
  isAuthenticated: (): boolean => {
    return !!localStorage.getItem('access_token');
  },
};
