import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import Layout from './components/Layout';
import Login from './pages/Login';
import Register from './pages/Register';
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

// Protected Route Component
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const isAuthenticated = authApi.isAuthenticated();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <Layout>{children}</Layout>;
};

// Admin Only Route
const AdminRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const isAuthenticated = authApi.isAuthenticated();
  const user = authApi.getCurrentUser();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== 'ADMIN') {
    return <Navigate to="/dashboard" replace />;
  }

  return <Layout>{children}</Layout>;
};

function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          <Route
            path="/order"
            element={
              <ProtectedRoute>
                <OrderPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/product"
            element={
              <ProtectedRoute>
                <ProductPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <SettlementDashboard />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin"
            element={
              <AdminRoute>
                <SettlementAdmin />
              </AdminRoute>
            }
          />
          <Route
            path="/games"
            element={
              <ProtectedRoute>
                <GamesPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/games/gomoku"
            element={
              <ProtectedRoute>
                <GomokuGame />
              </ProtectedRoute>
            }
          />
          <Route
            path="/games/baduk"
            element={
              <ProtectedRoute>
                <BadukGame />
              </ProtectedRoute>
            }
          />
          <Route
            path="/viewer"
            element={
              <ProtectedRoute>
                <ViewerPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/categories"
            element={
              <ProtectedRoute>
                <CategoryManagementPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/tags"
            element={
              <ProtectedRoute>
                <TagManagementPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/ecommerce-categories"
            element={
              <AdminRoute>
                <EcommerceCategoryAdmin />
              </AdminRoute>
            }
          />
          <Route path="/" element={<Navigate to="/order" replace />} />
        </Routes>
      </BrowserRouter>
    </ToastProvider>
  );
}

export default App;
