import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { LoginRequest } from '@/types';

const Login: React.FC = () => {
  const navigate = useNavigate();

  const [credentials, setCredentials] = useState<LoginRequest>({ email: '', password: '' });
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const response = await authApi.login(credentials);
      // ADMIN/MANAGER가 실수로 이 페이지에서 로그인하면 관리자 대시보드로
      if (response.role === 'ADMIN' || response.role === 'MANAGER') {
        authApi.saveToken(response);
        navigate('/admin');
      } else {
        authApi.saveToken(response);
        navigate('/order');
      }
    } catch (err: any) {
      setError(err.response?.data?.message || '이메일 또는 비밀번호가 올바르지 않습니다.');
    } finally {
      setLoading(false);
    }
  };

  /** 데모 자동로그인 (lemuel.demo.enabled=true 일 때만 작동, 그 외 404) */
  const handleAutoLogin = async (role: 'USER' | 'MANAGER' | 'ADMIN') => {
    setLoading(true);
    setError(null);
    try {
      const response = await authApi.autoLogin(role);
      authApi.saveToken(response);
      navigate(role === 'USER' ? '/order' : '/admin');
    } catch (err: any) {
      if (err.response?.status === 404) {
        setError('데모 모드가 비활성 상태입니다. (lemuel.demo.enabled=true 필요)');
      } else {
        setError(err.response?.data?.message || '자동 로그인에 실패했습니다.');
      }
    } finally {
      setLoading(false);
    }
  };

  /** 게스트 둘러보기 — 읽기 전용 토큰 발급 후 메인으로 */
  const handleGuestLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await authApi.guestLogin();
      authApi.saveToken(response);
      navigate('/');
    } catch (err: any) {
      if (err.response?.status === 404) {
        setError('게스트 모드가 비활성 상태입니다.');
      } else {
        setError(err.response?.data?.message || '게스트 진입에 실패했습니다.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md w-full space-y-8">

        {/* 헤더 */}
        <div>
          <button
            onClick={() => navigate('/admin/login')}
            className="mb-4 text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
            </svg>
            관리자 페이지로 가기
          </button>
          <h2 className="mt-2 text-center text-3xl font-extrabold text-gray-900">사용자 로그인</h2>
          <p className="mt-2 text-center text-sm text-gray-500">
            계정이 없으시면 먼저{' '}
            <button onClick={() => navigate('/register')} className="font-medium text-blue-600 hover:text-blue-500">
              회원가입
            </button>
            을 해주세요.
          </p>
        </div>

        {/* 폼 */}
        <form className="space-y-5" onSubmit={handleSubmit}>
          <div className="rounded-xl shadow-sm border border-gray-300 overflow-hidden">
            <input
              type="email"
              required
              autoComplete="email"
              placeholder="이메일"
              value={credentials.email}
              onChange={(e) => setCredentials({ ...credentials, email: e.target.value })}
              className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 border-b border-gray-300 focus:outline-none focus:ring-0 focus:border-blue-500"
            />
            <input
              type="password"
              required
              autoComplete="current-password"
              placeholder="비밀번호"
              value={credentials.password}
              onChange={(e) => setCredentials({ ...credentials, password: e.target.value })}
              className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-0"
            />
          </div>

          {error && (
            <div className="rounded-lg bg-red-50 border border-red-200 p-3">
              <p className="text-sm text-red-800">{error}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 px-4 bg-blue-600 text-white text-sm font-semibold rounded-xl hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? '로그인 중...' : '로그인'}
          </button>

          <div className="flex justify-between text-sm">
            <button
              type="button"
              onClick={() => navigate('/forgot-password')}
              className="text-gray-500 hover:text-blue-600"
            >
              비밀번호를 잊으셨나요?
            </button>
            <button
              type="button"
              onClick={() => navigate('/register')}
              className="font-medium text-blue-600 hover:text-blue-500"
            >
              회원가입 →
            </button>
          </div>
        </form>

        {/* 데모 / 빠른 진입 — 시연·둘러보기 용도 */}
        <div className="border-t border-gray-200 pt-6">
          <p className="text-center text-xs text-gray-500 mb-3">
            계정 없이 빠르게 둘러보고 싶으시다면 ↓
          </p>
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              disabled={loading}
              onClick={() => handleAutoLogin('USER')}
              className="py-2.5 px-3 text-sm font-medium rounded-lg border border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100 transition disabled:opacity-50"
            >
              👤 일반 사용자
            </button>
            <button
              type="button"
              disabled={loading}
              onClick={() => handleAutoLogin('MANAGER')}
              className="py-2.5 px-3 text-sm font-medium rounded-lg border border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 transition disabled:opacity-50"
            >
              🧑‍💼 매니저
            </button>
            <button
              type="button"
              disabled={loading}
              onClick={() => handleAutoLogin('ADMIN')}
              className="py-2.5 px-3 text-sm font-medium rounded-lg border border-purple-200 bg-purple-50 text-purple-700 hover:bg-purple-100 transition disabled:opacity-50"
            >
              👑 관리자
            </button>
            <button
              type="button"
              disabled={loading}
              onClick={handleGuestLogin}
              className="py-2.5 px-3 text-sm font-medium rounded-lg border border-gray-200 bg-gray-50 text-gray-700 hover:bg-gray-100 transition disabled:opacity-50"
            >
              🔍 게스트 둘러보기
            </button>
          </div>
          <p className="text-center text-[11px] text-gray-400 mt-3">
            데모 계정은 운영 환경에서는 비활성됩니다.
          </p>
        </div>

      </div>
    </div>
  );
};

export default Login;
