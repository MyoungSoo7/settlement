import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';

const QUICK_LOGINS = [
  { label: 'ADMIN 퀵 로그인',   email: 'seed_admin@test.com',   password: 'password123', color: 'purple' },
  { label: 'MANAGER 퀵 로그인', email: 'seed_manager@test.com', password: 'password123', color: 'green'  },
] as const;

const AdminLoginPage: React.FC = () => {
  const navigate = useNavigate();
  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const doLogin = async (creds: { email: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      const response = await authApi.login(creds);
      if (response.role !== 'ADMIN' && response.role !== 'MANAGER') {
        setError('관리자(ADMIN) 또는 매니저(MANAGER) 계정으로만 로그인할 수 있습니다.');
        return;
      }
      authApi.saveToken(response);
      navigate('/admin');
    } catch (err: any) {
      setError(err.response?.data?.message || '이메일 또는 비밀번호가 올바르지 않습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    doLogin({ email, password });
  };

  return (
    <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md">

        {/* 로고 */}
        <div className="text-center mb-8">
          <h1 className="text-4xl font-bold text-white italic mb-1">Lemuel</h1>
          <p className="text-gray-400 text-sm">관리자 시스템</p>
        </div>

        {/* 카드 */}
        <div className="bg-white rounded-2xl shadow-2xl p-8">
          <h2 className="text-xl font-bold text-gray-900 mb-1">관리자 로그인</h2>
          <p className="text-sm text-gray-400 mb-6">ADMIN / MANAGER 계정으로 로그인하세요.</p>

          {error && (
            <div className="mb-5 bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="rounded-xl border border-gray-300 overflow-hidden">
              <input
                type="email"
                required
                autoComplete="email"
                placeholder="이메일"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 border-b border-gray-300 focus:outline-none focus:ring-0"
              />
              <input
                type="password"
                required
                autoComplete="current-password"
                placeholder="비밀번호"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-0"
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full py-3 bg-gray-900 text-white text-sm font-semibold rounded-xl hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {loading ? '로그인 중...' : '로그인'}
            </button>
          </form>

          {/* 구분선 */}
          <div className="flex items-center my-5">
            <div className="flex-1 border-t border-gray-200" />
            <span className="mx-3 text-xs text-gray-400">테스트 계정</span>
            <div className="flex-1 border-t border-gray-200" />
          </div>

          {/* 퀵 로그인 */}
          <div className="space-y-2">
            {QUICK_LOGINS.map((q) => {
              const isPurple = q.color === 'purple';
              return (
                <button
                  key={q.email}
                  type="button"
                  onClick={() => doLogin({ email: q.email, password: q.password })}
                  disabled={loading}
                  className={`w-full py-2.5 text-sm font-semibold rounded-xl transition-colors disabled:opacity-50 ${
                    isPurple
                      ? 'bg-purple-100 text-purple-700 hover:bg-purple-200'
                      : 'bg-green-100 text-green-700 hover:bg-green-200'
                  }`}
                >
                  {q.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* 뒤로 가기 */}
        <div className="text-center mt-6">
          <button
            onClick={() => navigate('/')}
            className="text-gray-500 hover:text-gray-300 text-sm transition-colors"
          >
            ← 시작 화면으로
          </button>
        </div>
      </div>
    </div>
  );
};

export default AdminLoginPage;