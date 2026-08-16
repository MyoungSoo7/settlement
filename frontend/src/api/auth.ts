import api from './axios';
import { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from '@/types';
import { getAuthStorage } from '@/lib/authStorage';

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
   * 데모 자동로그인 (USER / MANAGER / ADMIN)
   * POST /auth/dev/auto-login?role=USER
   * 백엔드의 lemuel.demo.enabled=true 일 때만 200, 아니면 404.
   */
  autoLogin: async (role: 'USER' | 'MANAGER' | 'ADMIN'): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>(
      `/auth/dev/auto-login?role=${role}`
    );
    return response.data;
  },

  /**
   * 게스트 둘러보기 토큰 (DB 사용자 없음, 읽기 전용 화면용)
   * POST /auth/dev/guest
   */
  guestLogin: async (): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/dev/guest');
    return response.data;
  },

  /**
   * 로그아웃 (클라이언트 측)
   */
  logout: (): void => {
    void getAuthStorage().clear();
  },

  /**
   * 토큰 저장
   */
  saveToken: (loginResponse: LoginResponse): void => {
    // 기존 로그인 세션이 있는지 확인
    const storage = getAuthStorage();
    const existingEmail = storage.get('user_email');

    if (existingEmail && existingEmail !== loginResponse.email) {
      console.warn(`세션 교체: ${existingEmail} -> ${loginResponse.email}`);
      // 기존 세션 정보 제거
      authApi.logout();
    }

    // 새 세션 저장
    void Promise.all([
      storage.set('access_token', loginResponse.token),
      storage.set('user_email', loginResponse.email),
      storage.set('user_role', loginResponse.role),
      storage.set('login_timestamp', new Date().toISOString()),
    ]);
  },

  /**
   * 현재 사용자 정보 가져오기
   */
  getCurrentUser: (): { email: string; role: string } | null => {
    const storage = getAuthStorage();
    const email = storage.get('user_email');
    const role = storage.get('user_role');

    if (email && role) {
      return { email, role };
    }
    return null;
  },

  /**
   * 인증 여부 확인
   */
  isAuthenticated: (): boolean => {
    return !!getAuthStorage().get('access_token');
  },
};
