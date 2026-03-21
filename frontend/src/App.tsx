import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import { CartProvider } from './contexts/CartContext';
import Layout from './components/Layout';
import ChatWidget from './components/chat/ChatWidget';

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
const GamesPage = lazy(() => import('./pages/GamesPage'));
const GomokuGame = lazy(() => import('./pages/GomokuGame'));
const BadukGame = lazy(() => import('./pages/BadukGame'));
const ViewerPage = lazy(() => import('./pages/ViewerPage'));
const TossPaymentSuccess = lazy(() => import('./pages/TossPaymentSuccess'));

// 관리자 페이지 (lazy load)
const ProductPage = lazy(() => import('./pages/ProductPage'));
const SettlementDashboard = lazy(() => import('./pages/SettlementDashboardImproved'));
const SettlementAdmin = lazy(() => import('./pages/SettlementAdmin'));
const CategoryManagementPage = lazy(() => import('./pages/CategoryManagementPage'));
const TagManagementPage = lazy(() => import('./pages/TagManagementPage'));
const EcommerceCategoryAdmin = lazy(() => import('./pages/EcommerceCategoryAdmin'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));

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

            {/* ── 일반 사용자 (USER + 인증) ── */}
            <Route path="/order"    element={<ProtectedRoute><OrderPage /></ProtectedRoute>} />
            <Route path="/cart"     element={<ProtectedRoute><CartPage /></ProtectedRoute>} />
            <Route path="/mypage"   element={<ProtectedRoute><MyPage /></ProtectedRoute>} />
            <Route path="/games"    element={<ProtectedRoute><GamesPage /></ProtectedRoute>} />
            <Route path="/games/gomoku" element={<ProtectedRoute><GomokuGame /></ProtectedRoute>} />
            <Route path="/games/baduk"  element={<ProtectedRoute><BadukGame /></ProtectedRoute>} />
            <Route path="/viewer"   element={<ProtectedRoute><ViewerPage /></ProtectedRoute>} />
            <Route path="/order/toss/success" element={<ProtectedRoute><TossPaymentSuccess /></ProtectedRoute>} />

            {/* ── 관리자·매니저 공용 ── */}
            <Route path="/admin"              element={<AdminManagerRoute><AdminDashboardPage /></AdminManagerRoute>} />
            <Route path="/admin/settlement"   element={<AdminManagerRoute><SettlementAdmin /></AdminManagerRoute>} />
            <Route path="/settlement/search"   element={<AdminManagerRoute><SettlementDashboard /></AdminManagerRoute>} />
            <Route path="/product"            element={<AdminManagerRoute><ProductPage /></AdminManagerRoute>} />
            <Route path="/categories"         element={<AdminManagerRoute><CategoryManagementPage /></AdminManagerRoute>} />
            <Route path="/tags"               element={<AdminManagerRoute><TagManagementPage /></AdminManagerRoute>} />

            {/* ── 최고 관리자 전용 ── */}
            <Route path="/admin/system/ecommerce-categories"
              element={<AdminOnlyRoute><EcommerceCategoryAdmin /></AdminOnlyRoute>} />

          </Routes>
          <ChatWidget />
          </Suspense>
        </BrowserRouter>
      </CartProvider>
    </ToastProvider>
  );
}

export default App;