import React from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useCart } from '@/contexts/CartContext';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const user = authApi.getCurrentUser();
  const { totalCount } = useCart();

  const isAdminOrManager = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  const handleLogout = () => {
    authApi.logout();
    // ADMIN / MANAGER → 관리자 로그인, USER → 일반 로그인
    navigate(isAdminOrManager ? '/admin/login' : '/login');
  };

  const isActive = (path: string) =>
    location.pathname === path || location.pathname.startsWith(path + '/');

  const navLinkClass = (path: string) => {
    const base = 'px-4 py-2 rounded-lg font-medium transition-colors text-sm';
    return isActive(path)
      ? `${base} bg-blue-600 text-white`
      : `${base} text-gray-700 hover:bg-blue-50`;
  };

  const adminNavLinkClass = (path: string) => {
    const base = 'px-4 py-2 rounded-lg font-medium transition-colors text-sm';
    return isActive(path)
      ? `${base} bg-gray-800 text-white`
      : `${base} text-gray-600 hover:bg-gray-100`;
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className={`bg-white shadow ${isAdminOrManager ? 'border-b-2 border-gray-800' : ''}`}>
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center h-16">

            {/* 왼쪽: 로고 + 내비게이션 */}
            <div className="flex items-center space-x-6">
              <Link to={isAdminOrManager ? '/admin' : '/'} className="text-2xl font-bold text-blue-600">
                Lemuel
              </Link>

              {/* ── 관리자·매니저 내비 ── */}
              {user && isAdminOrManager && (
                <nav className="flex space-x-1">
                  <Link to="/admin"            className={adminNavLinkClass('/admin')}>대시보드</Link>
                  <Link to="/product"          className={adminNavLinkClass('/product')}>상품관리</Link>
                  <Link to="/admin/settlement" className={adminNavLinkClass('/admin/settlement')}>정산관리</Link>
                  <Link to="/dashboard"        className={adminNavLinkClass('/dashboard')}>정산조회</Link>
                  {user.role === 'ADMIN' && (
                    <Link to="/admin/system/ecommerce-categories" className={adminNavLinkClass('/admin/system')}>
                      시스템
                    </Link>
                  )}
                </nav>
              )}

              {/* ── 일반 사용자 내비 ── */}
              {user && !isAdminOrManager && (
                <nav className="flex space-x-1">
                  <Link to="/order"      className={navLinkClass('/order')}>주문하기</Link>
                  <Link to="/categories" className={navLinkClass('/categories')}>카테고리</Link>
                  <Link to="/tags"       className={navLinkClass('/tags')}>태그</Link>
                  <Link to="/games"      className={navLinkClass('/games')}>🎮 게임</Link>
                  <Link to="/viewer"     className={navLinkClass('/viewer')}>📖 뷰어</Link>
                </nav>
              )}
            </div>

            {/* 오른쪽: 사용자 정보 + 액션 */}
            {user && (
              <div className="flex items-center space-x-3">
                <div className="text-sm text-gray-700 flex items-center gap-2">
                  <span className="font-medium">{user.email}</span>
                  <span className={`px-2 py-0.5 rounded text-xs font-semibold ${
                    user.role === 'ADMIN'   ? 'bg-red-100 text-red-800'    :
                    user.role === 'MANAGER' ? 'bg-purple-100 text-purple-800' :
                                              'bg-blue-100 text-blue-800'
                  }`}>
                    {user.role}
                  </span>
                </div>

                {/* 장바구니 — USER 전용 */}
                {user.role === 'USER' && (
                  <Link
                    to="/cart"
                    className="relative p-2 text-gray-600 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                    title="장바구니"
                  >
                    <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                        d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z" />
                    </svg>
                    {totalCount > 0 && (
                      <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center px-0.5">
                        {totalCount > 99 ? '99+' : totalCount}
                      </span>
                    )}
                  </Link>
                )}

                {/* 마이페이지 — USER 전용 */}
                {user.role === 'USER' && (
                  <Link
                    to="/mypage"
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold transition-colors ${
                      isActive('/mypage')
                        ? 'bg-blue-600 text-white'
                        : 'text-gray-600 hover:text-blue-600 hover:bg-blue-50'
                    }`}
                    title="마이페이지"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                        d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                    </svg>
                    MY
                  </Link>
                )}

                <button
                  onClick={handleLogout}
                  className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors text-sm"
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
