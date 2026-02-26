import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { LoginRequest } from '@/types';

const Login: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const queryParams = new URLSearchParams(location.search);
  const requestedRole = queryParams.get('role') || 'USER';

  const [credentials, setCredentials] = useState<LoginRequest>({
    email: '',
    password: '',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [existingSession, setExistingSession] = useState<string | null>(null);

  // 컴포넌트 마운트 시 기존 세션 확인 및 롤에 따른 초기값 설정 (선택사항)
  React.useEffect(() => {
    const currentUser = authApi.getCurrentUser();
    if (currentUser) {
      setExistingSession(currentUser.email);
    }

    // 역할을 명시적으로 요청한 경우 해당 프리셋으로 채워줄 수도 있음
    if (requestedRole === 'ADMIN') {
      setCredentials({ email: 'seed_admin@test.com', password: 'password123' });
    } else if (requestedRole === 'MANAGER') {
      setCredentials({ email: 'seed_manager@test.com', password: 'password123' });
    } else if (requestedRole === 'USER') {
       // 유저는 여러 명일 수 있으니 하나만 예시로
       setCredentials({ email: 'seed_user1@test.com', password: 'password123' });
    }
  }, [requestedRole]);

  const handleQuickLogin = (email: string) => {
    setCredentials({ email, password: 'password123' });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const response = await authApi.login(credentials);
      authApi.saveToken(response);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || '로그인에 실패했습니다.');
      console.error('Login error:', err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md w-full space-y-8">
        <div>
          <button 
            onClick={() => navigate('/')}
            className="mb-4 text-sm text-blue-600 hover:text-blue-800 flex items-center"
          >
            <svg className="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
            </svg>
            시작 화면으로 돌아가기
          </button>
          <h2 className="mt-2 text-center text-3xl font-extrabold text-gray-900">Lemuel</h2>
          <p className="mt-2 text-center text-sm text-gray-600">
            {requestedRole === 'ADMIN' ? '관리자 시스템 로그인' : 
             requestedRole === 'MANAGER' ? '매니저 시스템 로그인' : 
             '사용자 쇼핑 로그인'}
          </p>
        </div>

        {/* Quick Login Presets */}
        <div className="bg-white p-4 rounded-lg shadow-sm border border-gray-200 space-y-3">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider text-center">빠른 로그인 (테스트용)</p>
          <div className="grid grid-cols-3 gap-2">
            <button
              type="button"
              onClick={() => handleQuickLogin('seed_user1@test.com')}
              className={`py-2 px-2 text-xs font-medium rounded border ${requestedRole === 'USER' ? 'bg-blue-50 border-blue-200 text-blue-700' : 'bg-gray-50 border-gray-200 text-gray-700 hover:bg-gray-100'}`}
            >
              일반 사용자
            </button>
            <button
              type="button"
              onClick={() => handleQuickLogin('seed_manager@test.com')}
              className={`py-2 px-2 text-xs font-medium rounded border ${requestedRole === 'MANAGER' ? 'bg-green-50 border-green-200 text-green-700' : 'bg-gray-50 border-gray-200 text-gray-700 hover:bg-gray-100'}`}
            >
              매니저
            </button>
            <button
              type="button"
              onClick={() => handleQuickLogin('seed_admin@test.com')}
              className={`py-2 px-2 text-xs font-medium rounded border ${requestedRole === 'ADMIN' ? 'bg-purple-50 border-purple-200 text-purple-700' : 'bg-gray-50 border-gray-200 text-gray-700 hover:bg-gray-100'}`}
            >
              관리자
            </button>
          </div>
        </div>

        <form className="mt-4 space-y-6" onSubmit={handleSubmit}>
          <div className="rounded-md shadow-sm -space-y-px">
            <div>
              <label htmlFor="email" className="sr-only">
                이메일
              </label>
              <input
                id="email"
                name="email"
                type="email"
                required
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-t-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="이메일"
                value={credentials.email}
                onChange={(e) => setCredentials({ ...credentials, email: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor="password" className="sr-only">
                비밀번호
              </label>
              <input
                id="password"
                name="password"
                type="password"
                required
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-b-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="비밀번호"
                value={credentials.password}
                onChange={(e) => setCredentials({ ...credentials, password: e.target.value })}
              />
            </div>
          </div>

          {existingSession && (
            <div className="rounded-md bg-yellow-50 border border-yellow-200 p-4">
              <div className="flex items-start">
                <div className="flex-shrink-0">
                  <svg className="h-5 w-5 text-yellow-400" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                  </svg>
                </div>
                <div className="ml-3">
                  <p className="text-sm text-yellow-800">
                    현재 <strong>{existingSession}</strong> 계정으로 로그인되어 있습니다.
                    <br />
                    다른 계정으로 로그인하면 기존 세션이 종료됩니다.
                  </p>
                </div>
              </div>
            </div>
          )}

          {error && (
            <div className="rounded-md bg-red-50 p-4">
              <p className="text-sm text-red-800">{error}</p>
            </div>
          )}

          <div>
            <button
              type="submit"
              disabled={loading}
              className="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
            >
              {loading ? '로그인 중...' : '로그인'}
            </button>
          </div>

          <div className="flex flex-col space-y-3">
            <div className="text-center">
              <button
                type="button"
                onClick={() => navigate('/forgot-password')}
                className="text-sm font-medium text-blue-600 hover:text-blue-500"
              >
                비밀번호를 잊으셨나요?
              </button>
            </div>
            <div className="text-center">
              <p className="text-sm text-gray-600">
                계정이 없으신가요?{' '}
                <button
                  type="button"
                  onClick={() => navigate('/register')}
                  className="font-medium text-blue-600 hover:text-blue-500"
                >
                  회원가입
                </button>
              </p>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
};

export default Login;
