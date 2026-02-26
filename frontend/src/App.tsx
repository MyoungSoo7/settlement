import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import { CartProvider } from './contexts/CartContext';
import Layout from './components/Layout';
import Login from './pages/Login';
import AdminLoginPage from './pages/AdminLoginPage';
import Register from './pages/Register';
import StartPage from './pages/StartPage';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import OrderPage from './pages/OrderPage';
import ProductPage from './pages/ProductPage';
import SettlementDashboard from './pages/SettlementDashboardImproved';
import SettlementAdmin from './pages/SettlementAdmin';
import GamesPage from './pages/GamesPage';
import GomokuGame from './pages/GomokuGame';
import BadukGame from './pages/BadukGame';
import ViewerPage from './pages/ViewerPage';
import CategoryManagementPage from './pages/CategoryManagementPage';
import TagManagementPage from './pages/TagManagementPage';
import EcommerceCategoryAdmin from './pages/EcommerceCategoryAdmin';
import TossPaymentSuccess from './pages/TossPaymentSuccess';
import TossPaymentFail from './pages/TossPaymentFail';
import CartPage from './pages/CartPage';
import MyPage from './pages/MyPage';
import AdminDashboardPage from './pages/AdminDashboardPage';

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
          <Routes>

            {/* ── 공개 (인증 불필요) ── */}
            <Route path="/"                   element={<StartPage />} />
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
            <Route path="/dashboard"          element={<AdminManagerRoute><SettlementDashboard /></AdminManagerRoute>} />
            <Route path="/product"            element={<AdminManagerRoute><ProductPage /></AdminManagerRoute>} />
            <Route path="/categories"         element={<AdminManagerRoute><CategoryManagementPage /></AdminManagerRoute>} />
            <Route path="/tags"               element={<AdminManagerRoute><TagManagementPage /></AdminManagerRoute>} />

            {/* ── 최고 관리자 전용 ── */}
            <Route path="/admin/system/ecommerce-categories"
              element={<AdminOnlyRoute><EcommerceCategoryAdmin /></AdminOnlyRoute>} />

          </Routes>
        </BrowserRouter>
      </CartProvider>
    </ToastProvider>
  );
}

export default App;