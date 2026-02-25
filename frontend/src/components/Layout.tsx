import React from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const user = authApi.getCurrentUser();

  const handleLogout = () => {
    authApi.logout();
    navigate('/login');
  };

  const isActive = (path: string) => {
    return location.pathname === path;
  };

  const navLinkClass = (path: string) => {
    const base = 'px-4 py-2 rounded-lg font-medium transition-colors';
    return isActive(path)
      ? `${base} bg-blue-600 text-white`
      : `${base} text-gray-700 hover:bg-blue-50`;
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white shadow">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center h-16">
            <div className="flex items-center space-x-8">
              <Link to="/" className="text-2xl font-bold text-blue-600">
                Lemuel
              </Link>

              {user && (
                <nav className="flex space-x-4">
                  <Link to="/order" className={navLinkClass('/order')}>
                    주문하기
                  </Link>
                  <Link to="/product" className={navLinkClass('/product')}>
                    상품관리
                  </Link>
                  <Link to="/categories" className={navLinkClass('/categories')}>
                    카테고리
                  </Link>
                  <Link to="/tags" className={navLinkClass('/tags')}>
                    태그
                  </Link>
                  <Link to="/dashboard" className={navLinkClass('/dashboard')}>
                    정산 조회
                  </Link>
                  <Link to="/games" className={navLinkClass('/games')}>
                    🎮 게임
                  </Link>
                  <Link to="/viewer" className={navLinkClass('/viewer')}>
                    📖 뷰어
                  </Link>
                  {user.role === 'ADMIN' && (
                    <Link to="/admin" className={navLinkClass('/admin')}>
                      관리자
                    </Link>
                  )}
                </nav>
              )}
            </div>

            {user && (
              <div className="flex items-center space-x-4">
                <div className="text-sm text-gray-700">
                  <span className="font-medium">{user.email}</span>
                  <span className="ml-2 px-2 py-1 bg-blue-100 text-blue-800 rounded text-xs">
                    {user.role}
                  </span>
                </div>
                <button
                  onClick={handleLogout}
                  className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors"
                >
                  로그아웃
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main>{children}</main>

      {/* Footer */}
      <footer className="bg-white border-t mt-12">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
          <div className="text-center text-gray-600">
            <p className="text-sm">© 2024 Lemuel Settlement System. All rights reserved.</p>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default Layout;
