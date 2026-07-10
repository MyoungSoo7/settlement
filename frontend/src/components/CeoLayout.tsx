import React from 'react';
import { Link, useLocation } from 'react-router-dom';

interface CeoLayoutProps {
  children: React.ReactNode;
}

interface SidebarItem {
  to: string;
  label: string;
  icon: string;
  desc: string;
}

const ITEMS: SidebarItem[] = [
  { to: '/admin/ceo/insight',    label: '통합 브리핑', icon: 'CEO', desc: '재무·평판·경제 통합' },
  { to: '/admin/ceo/economics',  label: '경제지표', icon: '📈', desc: '한국은행 ECOS 지표' },
  { to: '/admin/ceo/financials', label: '재무제표', icon: '📊', desc: '코스피 상장사 재무제표' },
  { to: '/admin/ceo/companies',  label: '기업조회', icon: '📰', desc: '기업 뉴스 · 평판' },
  { to: '/admin/ceo/invest',     label: '투자하기', icon: '💹', desc: '재무점수 기반 투자' },
  { to: '/admin/ceo/loans',      label: '대출관리', icon: '💸', desc: '선정산 · 기업대출' },
  { to: '/admin/ceo/accounts',   label: '계정계 현황', icon: '🧮', desc: '집계 · 시산표 · 잔액' },
];

const CeoLayout: React.FC<CeoLayoutProps> = ({ children }) => {
  const location = useLocation();

  const isActive = (to: string) =>
    location.pathname === to || location.pathname.startsWith(to + '/');

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="flex flex-col lg:flex-row gap-6">

          {/* ── 좌측 사이드바 ─────────────────────────────────────────── */}
          <aside className="w-full lg:w-64 flex-shrink-0">
            <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden lg:sticky lg:top-8">
              <div className="px-5 py-4 border-b border-gray-100 bg-gray-900">
                <p className="text-white font-bold flex items-center gap-2">
                  <span>👔</span> CEO
                </p>
                <p className="text-gray-400 text-xs mt-0.5">Executive Insights</p>
              </div>
              <nav className="p-2 space-y-1">
                {ITEMS.map((item) => {
                  const active = isActive(item.to);
                  return (
                    <Link
                      key={item.to}
                      to={item.to}
                      className={`flex items-start gap-3 px-3 py-2.5 rounded-lg transition-colors ${
                        active
                          ? 'bg-gray-900 text-white'
                          : 'text-gray-700 hover:bg-gray-100'
                      }`}
                    >
                      <span className="text-lg leading-none mt-0.5">{item.icon}</span>
                      <span className="flex-1 min-w-0">
                        <span className="block text-sm font-semibold">{item.label}</span>
                        <span className={`block text-xs mt-0.5 ${active ? 'text-gray-300' : 'text-gray-400'}`}>
                          {item.desc}
                        </span>
                      </span>
                    </Link>
                  );
                })}
              </nav>
            </div>
          </aside>

          {/* ── 우측 콘텐츠 ──────────────────────────────────────────── */}
          <main className="flex-1 min-w-0">{children}</main>
        </div>
      </div>
    </div>
  );
};

export default CeoLayout;
