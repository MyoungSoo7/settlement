import api from './axios';
import { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from '@/types';

export const authApi = {
  /**
   * 로그인
   * POST /auth/login
   */
  login: async (credentials: LoginRequest): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/login', credentials);
    return response.data;
  },

  /**
   * 회원가입
   * POST /users
   */
  register: async (userData: RegisterRequest): Promise<UserResponse> => {
    const response = await api.post<UserResponse>('/users', userData);
    return response.data;
  },

  /**
   * 로그아웃 (클라이언트 측)
   */
  logout: (): void => {
    localStorage.removeItem('access_token');
    localStorage.removeItem('user_email');
    localStorage.removeItem('user_role');
  },

  /**
   * 토큰 저장
   */
  saveToken: (loginResponse: LoginResponse): void => {
    localStorage.setItem('access_token', loginResponse.token);
    localStorage.setItem('user_email', loginResponse.email);
    localStorage.setItem('user_role', loginResponse.role);
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
