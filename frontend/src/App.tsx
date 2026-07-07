import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import { CartProvider } from './contexts/CartContext';
import Layout from './components/Layout';
import SystemLayout from './components/SystemLayout';
import CeoLayout from './components/CeoLayout';

// 공개 페이지 (즉시 로드)
import Login from './pages/Login';
import AdminLoginPage from './pages/AdminLoginPage';
import Register from './pages/Register';
import TossPaymentFail from './pages/TossPaymentFail';

// 인증 필요 페이지 (lazy load)
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const OrderPage = lazy(() => import('./pages/OrderPage'));
const CartPage = lazy(() => import('./pages/CartPage'));
const MyPage = lazy(() => import('./pages/MyPage'));
const LoanPage = lazy(() => import('./pages/LoanPage'));
const TossPaymentSuccess = lazy(() => import('./pages/TossPaymentSuccess'));
const FinancialStatementsPage = lazy(() => import('./pages/FinancialStatementsPage'));
const EconomicsPage = lazy(() => import('./pages/EconomicsPage'));
const CompanyLookupPage = lazy(() => import('./pages/CompanyLookupPage'));
const CeoInsightPage = lazy(() => import('./pages/CeoInsightPage'));

// 관리자 페이지 (lazy load)
const ProductPage = lazy(() => import('./pages/ProductPage'));
const SettlementDashboard = lazy(() => import('./pages/SettlementDashboardImproved'));
const SettlementAdmin = lazy(() => import('./pages/SettlementAdmin'));
const CategoryManagementPage = lazy(() => import('./pages/CategoryManagementPage'));
const TagManagementPage = lazy(() => import('./pages/TagManagementPage'));
const EcommerceCategoryAdmin = lazy(() => import('./pages/EcommerceCategoryAdmin'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));

// 운영 관제 (최고 관리자 전용) — operation-service 인시던트 콘솔
const OperationConsolePage = lazy(() => import('./pages/operation/OperationConsolePage'));

// 시스템 관리 (최고 관리자 전용, 좌측 사이드바)
const MenuManagementPage = lazy(() => import('./pages/system/MenuManagementPage'));
const CommonCodeManagementPage = lazy(() => import('./pages/system/CommonCodeManagementPage'));
const RbacManagementPage = lazy(() => import('./pages/system/RbacManagementPage'));

// ── 일반 사용자용 (인증 필수, 역할 무관) ──────────────────────────────────
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <Layout>{children}</Layout>;
};

// ── 관리자·매니저 공용 (/admin/settlement/**, /admin, /product 등) ─────────
const AdminManagerRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const user = authApi.getCurrentUser();
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/admin/login" replace />;
  }
  if (user?.role !== 'ADMIN' && user?.role !== 'MANAGER') {
    return <Navigate to="/login" replace />;
  }
  return <Layout>{children}</Layout>;
};

// ── 최고 관리자 전용 (/admin/system/**, 회원관리 등) ──────────────────────
const AdminOnlyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const user = authApi.getCurrentUser();
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/admin/login" replace />;
  }
  if (user?.role !== 'ADMIN') {
    return <Navigate to="/admin" replace />;
  }
  return <Layout>{children}</Layout>;
};

function App() {
  return (
    <ToastProvider>
      <CartProvider>
        <BrowserRouter>
          <Suspense fallback={<div className="flex justify-center items-center min-h-screen"><div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div></div>}>
          <Routes>

            {/* ── 공개 (인증 불필요) ── */}
            <Route path="/"                   element={<Navigate to="/login" replace />} />
            <Route path="/login"              element={<Login />} />
            <Route path="/admin/login"        element={<AdminLoginPage />} />
            <Route path="/register"           element={<Register />} />
            <Route path="/forgot-password"    element={<ForgotPassword />} />
            <Route path="/reset-password"     element={<ResetPassword />} />
            <Route path="/order/toss/fail"    element={<TossPaymentFail />} />
            {/* 코스피 재무제표 — 공시 데이터라 공개 (관리자 헤더 메뉴 '재무제표' 진입, Layout 로 헤더 유지) */}
            <Route path="/financials"         element={<Layout><FinancialStatementsPage /></Layout>} />
            {/* 기업 뉴스·평판 조회 (ADR 0023) — 공개 조회 API, 관리자 헤더 메뉴 '기업조회' 진입, Layout 유지 */}
            <Route path="/companies"          element={<Layout><CompanyLookupPage /></Layout>} />
            {/* 한국은행 ECOS 경제지표 — 공공 데이터라 공개. 모든 사용자가 헤더 메뉴로 접근·왕복하도록 Layout 유지 */}
            <Route path="/economics"          element={<Layout><EconomicsPage /></Layout>} />

            {/* ── 일반 사용자 (USER + 인증) ── */}
            <Route path="/order"        element={<ProtectedRoute><OrderPage /></ProtectedRoute>} />
            <Route path="/cart"         element={<ProtectedRoute><CartPage /></ProtectedRoute>} />
            <Route path="/mypage"       element={<ProtectedRoute><MyPage /></ProtectedRoute>} />
            <Route path="/loans"        element={<ProtectedRoute><LoanPage /></ProtectedRoute>} />
            <Route path="/order/toss/success" element={<ProtectedRoute><TossPaymentSuccess /></ProtectedRoute>} />

            {/* ── 관리자·매니저 공용 ── */}
            <Route path="/admin"              element={<AdminManagerRoute><AdminDashboardPage /></AdminManagerRoute>} />
            <Route path="/admin/settlement"   element={<AdminManagerRoute><SettlementAdmin /></AdminManagerRoute>} />
            <Route path="/settlement/search"   element={<AdminManagerRoute><SettlementDashboard /></AdminManagerRoute>} />
            <Route path="/product"            element={<AdminManagerRoute><ProductPage /></AdminManagerRoute>} />
            <Route path="/categories"         element={<AdminManagerRoute><CategoryManagementPage /></AdminManagerRoute>} />
            <Route path="/tags"               element={<AdminManagerRoute><TagManagementPage /></AdminManagerRoute>} />

            {/* ── 최고 관리자 전용: 운영 관제 — 시스템 관리(운영관리)로 편입, 구 경로는 리다이렉트 ── */}
            <Route path="/admin/operation"
              element={<Navigate to="/admin/system/operation" replace />} />

            {/* ── 최고 관리자 전용: 시스템 관리 (좌측 사이드바) ── */}
            <Route path="/admin/system"
              element={<Navigate to="/admin/system/menus" replace />} />
            <Route path="/admin/system/menus"
              element={<AdminOnlyRoute><SystemLayout><MenuManagementPage /></SystemLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/codes"
              element={<AdminOnlyRoute><SystemLayout><CommonCodeManagementPage /></SystemLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/rbac"
              element={<AdminOnlyRoute><SystemLayout><RbacManagementPage /></SystemLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/ecommerce-categories"
              element={<AdminOnlyRoute><SystemLayout><EcommerceCategoryAdmin /></SystemLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/operation"
              element={<AdminOnlyRoute><SystemLayout><OperationConsolePage /></SystemLayout></AdminOnlyRoute>} />

            {/* ── 최고 관리자 전용: CEO 인사이트 (좌측 사이드바) — 위성 조회 서비스 묶음 ── */}
            <Route path="/admin/ceo"
              element={<Navigate to="/admin/ceo/insight" replace />} />
            <Route path="/admin/ceo/insight"
              element={<AdminOnlyRoute><CeoLayout><CeoInsightPage /></CeoLayout></AdminOnlyRoute>} />
            <Route path="/admin/ceo/economics"
              element={<AdminOnlyRoute><CeoLayout><EconomicsPage /></CeoLayout></AdminOnlyRoute>} />
            <Route path="/admin/ceo/financials"
              element={<AdminOnlyRoute><CeoLayout><FinancialStatementsPage /></CeoLayout></AdminOnlyRoute>} />
            <Route path="/admin/ceo/companies"
              element={<AdminOnlyRoute><CeoLayout><CompanyLookupPage /></CeoLayout></AdminOnlyRoute>} />
            <Route path="/admin/ceo/loans"
              element={<AdminOnlyRoute><CeoLayout><LoanPage /></CeoLayout></AdminOnlyRoute>} />

          </Routes>
          </Suspense>
        </BrowserRouter>
      </CartProvider>
    </ToastProvider>
  );
}

export default App;
