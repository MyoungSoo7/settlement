import api from './axios';
import {
  SocialLoginRequest,
  SocialLoginResponse,
  SocialAccountResponse,
  SocialProvider,
} from '@/types';

export const socialLoginApi = {
  /**
   * 소셜 로그인
   * POST /api/auth/social/login
   */
  login: async (request: SocialLoginRequest): Promise<SocialLoginResponse> => {
    const response = await api.post<SocialLoginResponse>('/api/auth/social/login', request);
    return response.data;
  },

  /**
   * 소셜 계정 연동
   * POST /api/auth/social/link
   */
  linkAccount: async (
    userId: number,
    provider: SocialProvider,
    code: string,
    redirectUri: string
  ): Promise<SocialAccountResponse> => {
    const response = await api.post<SocialAccountResponse>('/api/auth/social/link', {
      userId,
      provider,
      code,
      redirectUri,
    });
    return response.data;
  },

  /**
   * 소셜 계정 연동 해제
   * DELETE /api/auth/social/unlink
   */
  unlinkAccount: async (userId: number, provider: SocialProvider): Promise<void> => {
    await api.delete('/api/auth/social/unlink', {
      data: { userId, provider },
    });
  },

  /**
   * 사용자의 소셜 계정 목록 조회
   * GET /api/auth/social/{userId}/accounts
   */
  getAccounts: async (userId: number): Promise<SocialAccountResponse[]> => {
    const response = await api.get<SocialAccountResponse[]>(
      `/api/auth/social/${userId}/accounts`
    );
    return response.data;
  },
};
