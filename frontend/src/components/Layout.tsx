import React, { useEffect, useRef } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useCart } from '@/contexts/useCart';

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

  /**
   * 내비 항목 속성. `shrink-0` 은 가로 스크롤 스트립 안에서 항목이 찌그러지지 않게 하고,
   * `aria-current` 는 스크린리더에 현재 위치를 알리는 동시에 아래 자동 스크롤의 선택자가 된다.
   */
  const navItemProps = (active: boolean, variant: 'admin' | 'user') => ({
    'aria-current': active ? ('page' as const) : undefined,
    className: [
      'px-4 py-2 rounded-lg font-medium transition-colors text-sm shrink-0',
      active
        ? (variant === 'admin' ? 'bg-gray-800 text-white' : 'bg-blue-600 text-white')
        : (variant === 'admin' ? 'text-gray-600 hover:bg-gray-100' : 'text-gray-700 hover:bg-blue-50'),
    ].join(' '),
  });

  // 정산 그룹(상품관리·정산관리·정산조회) — 세 경로 중 어디에 있어도 활성
  const settlementActive =
    isActive('/product') || isActive('/admin/settlement') || isActive('/settlement/search');

  /**
   * 좁은 화면에서 내비는 가로 스크롤 스트립이라 뒤쪽 항목이 화면 밖에 있다. 그대로 두면 사용자가
   * 지금 어느 섹션에 있는지 볼 수 없으므로, 경로가 바뀔 때마다 활성 항목을 스트립 안으로 끌어온다.
   * `block: 'nearest'` 라 세로 스크롤은 건드리지 않고, 넓은 화면에서는 이미 다 보이므로 무동작이다.
   */
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    navRef.current
      ?.querySelector('[aria-current="page"]')
      ?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, [location.pathname]);

  return (
    /* `px-safe` 는 가로 모드에서 노치가 먹는 좌/우 폭을 셸 바깥쪽에 확보한다. 안쪽 컨테이너의
       `px-4 sm:px-6 lg:px-8` 과 겹치지 않게 **패딩이 없는 최외곽**에만 건다(겹쳐 걸면 Tailwind
       유틸리티가 덮여 좌우 여백 자체가 사라진다). 배경은 패딩 아래까지 칠해지므로 흰 띠는 없다. */
    <div className="min-h-screen bg-gray-50 px-safe">
      {/* Header — 설치형(standalone)에서는 상태바가 문서 위에 겹쳐 뜨므로(black-translucent)
          헤더가 그만큼 아래에서 시작해야 로고·내비가 시계·배터리에 가리지 않는다. */}
      <header className={`bg-white shadow pt-safe ${isAdminOrManager ? 'border-b-2 border-gray-800' : ''}`}>
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center h-16">

            {/* 왼쪽: 로고 + 내비게이션.
                `min-w-0` 이 없으면 flex 아이템의 기본 최소 폭(min-content) 때문에 좁은 화면에서
                이 블록이 줄어들지 못하고 문서 전체를 밀어 가로 스크롤을 만든다(390px 에서 문서
                634px 로 실측). 로고는 줄이지 않고, 남는 폭이 모자라면 내비를 가로 스크롤시킨다. */}
            <div className="flex items-center space-x-6 min-w-0">
              <Link to={isAdminOrManager ? '/admin' : '/'} className="text-2xl font-bold text-blue-600 shrink-0">
                Lemuel
              </Link>

              {/* ── 관리자·매니저 내비 ── */}
              {user && isAdminOrManager && (
                <nav ref={navRef} className="flex space-x-1 min-w-0 overflow-x-auto whitespace-nowrap [scrollbar-width:thin]">
                  {/* 대시보드만 정확 매칭 — 정산·CEO·시스템이 전부 /admin 하위라 접두 매칭이면
                      어느 섹션에 있든 대시보드까지 함께 활성으로 칠해진다(활성 항목 2개 → 현재
                      위치가 흐려지고, aria-current 도 둘이 된다). */}
                  <Link to="/admin" {...navItemProps(location.pathname === '/admin', 'admin')}>대시보드</Link>
                  {/* 정산 — 상품관리·정산관리·정산조회 3개 하위 (좌측 사이드바) */}
                  <Link to="/admin/settlement" {...navItemProps(settlementActive, 'admin')}>
                    정산
                  </Link>
                  <Link to="/ai/chat" {...navItemProps(isActive('/ai/chat'), 'admin')}>AI 도우미</Link>
                  {/* CEO — ADMIN·MANAGER 공통 (경제지표·재무제표·기업조회·대출관리 4개 하위, 좌측 사이드바) */}
                  <Link to="/admin/ceo/insight" {...navItemProps(isActive('/admin/ceo'), 'admin')}>
                    CEO
                  </Link>
                  {/* 시스템 — ADMIN 전용 */}
                  {user.role === 'ADMIN' && (
                    <Link to="/admin/system/menus" {...navItemProps(isActive('/admin/system'), 'admin')}>
                      시스템
                    </Link>
                  )}
                </nav>
              )}

              {/* ── 일반 사용자 내비 ── 주문하기 + 추천받기 (USER 전용) */}
              {user && !isAdminOrManager && (
                <nav ref={navRef} className="flex space-x-1 min-w-0 overflow-x-auto whitespace-nowrap [scrollbar-width:thin]">
                  <Link to="/order" {...navItemProps(isActive('/order'), 'user')}>주문하기</Link>
                  {user.role === 'USER' && (
                    <Link to="/recommend" {...navItemProps(isActive('/recommend'), 'user')}>추천받기</Link>
                  )}
                </nav>
              )}
            </div>

            {/* 오른쪽: 사용자 정보 + 액션. 내비와 달리 이쪽은 줄이지 않는다(`shrink-0`) —
                로그아웃은 어느 폭에서도 눌러야 한다. 대신 좁은 화면에서는 이메일을 숨기고
                역할 뱃지만 남긴다(이메일 253px 중 129px). 숨긴 값은 뱃지 title 로 확인할 수 있다. */}
            {user && (
              <div className="flex items-center space-x-3 shrink-0">
                <div className="text-sm text-gray-700 flex items-center gap-2">
                  <span className="font-medium hidden sm:inline">{user.email}</span>
                  <span
                    title={user.email}
                    className={`px-2 py-0.5 rounded text-xs font-semibold ${
                      user.role === 'ADMIN'   ? 'bg-red-100 text-red-800'    :
                      user.role === 'MANAGER' ? 'bg-purple-100 text-purple-800' :
                                                'bg-blue-100 text-blue-800'
                    }`}
                  >
                    {user.role}
                  </span>
                </div>

                {/* 장바구니 — USER 전용 */}
                {user.role === 'USER' && (
                  <Link
                    to="/cart"
                    /* tap-target: 실측 40×40 — 터치 환경에서만 눌리는 영역을 44×44 로 넓힌다. */
                    className="tap-target relative p-2 text-gray-600 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
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
      {/* 홈 인디케이터(하단 바)가 문서 위에 겹치는 기기에서 푸터 문구가 가리지 않게 띄운다. */}
      <footer className="bg-white border-t mt-12 pb-safe">
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
