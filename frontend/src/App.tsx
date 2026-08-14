import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import { AuthProvider } from './contexts/AuthContext';
import { MenuProvider } from './contexts/MenuContext';
import { CartProvider } from './contexts/CartContext';
import Layout from './components/Layout';
import SideNavLayout from './components/SideNavLayout';

// 공개 페이지 (즉시 로드)
import Login from './pages/Login';
import AdminLoginPage from './pages/AdminLoginPage';
import Register from './pages/Register';
import TossPaymentFail from './pages/TossPaymentFail';

// 인증 필요 페이지 (lazy load)
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const OrderPage = lazy(() => import('./pages/OrderPage'));
const RecommendPage = lazy(() => import('./pages/RecommendPage'));
const CartPage = lazy(() => import('./pages/CartPage'));
const MyPage = lazy(() => import('./pages/MyPage'));
const LoanPage = lazy(() => import('./pages/LoanPage'));
const TossPaymentSuccess = lazy(() => import('./pages/TossPaymentSuccess'));
const FinancialStatementsPage = lazy(() => import('./pages/FinancialStatementsPage'));
const EconomicsPage = lazy(() => import('./pages/EconomicsPage'));
const CompanyLookupPage = lazy(() => import('./pages/CompanyLookupPage'));
const WorkforcePage = lazy(() => import('./pages/WorkforcePage'));
const CeoInsightPage = lazy(() => import('./pages/CeoInsightPage'));
const CeoInvestPage = lazy(() => import('./pages/CeoInvestPage'));
const CeoInvestRecommendPage = lazy(() => import('./pages/CeoInvestRecommendPage'));
const CeoAccountPage = lazy(() => import('./pages/CeoAccountPage'));
const CeoLoanGuidePage = lazy(() => import('./pages/CeoLoanGuidePage'));
const CeoLoanProcessGuidePage = lazy(() => import('./pages/CeoLoanProcessGuidePage'));
const CeoLenderGuidePage = lazy(() => import('./pages/CeoLenderGuidePage'));
const CeoFundGuidePage = lazy(() => import('./pages/CeoFundGuidePage'));
const AiChatPage = lazy(() => import('./pages/AiChatPage'));

// 관리자 페이지 (lazy load)
const ProductPage = lazy(() => import('./pages/ProductPage'));
const SettlementDashboard = lazy(() => import('./pages/SettlementDashboardImproved'));
const SettlementAdmin = lazy(() => import('./pages/SettlementAdmin'));
const TagManagementPage = lazy(() => import('./pages/TagManagementPage'));
const EcommerceCategoryAdmin = lazy(() => import('./pages/EcommerceCategoryAdmin'));
const DisplaySectionAdminPage = lazy(() => import('./pages/DisplaySectionAdminPage'));
const OptionCatalogAdminPage = lazy(() => import('./pages/OptionCatalogAdminPage'));
const ProofReviewQueuePage = lazy(() => import('./pages/ProofReviewQueuePage'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));
const ShippingAdminPage = lazy(() => import('./pages/ShippingAdminPage'));
const OrderApprovalPage = lazy(() => import('./pages/OrderApprovalPage'));
const PayoutAdminPage = lazy(() => import('./pages/PayoutAdminPage'));

// 정산운영 콘솔 — settlement-service 운영 API(/admin/**)를 화면으로 노출한다.
const IntegrityConsolePage = lazy(() => import('./pages/settlement/IntegrityConsolePage'));
const ReconciliationConsolePage = lazy(() => import('./pages/settlement/ReconciliationConsolePage'));
const LedgerConsolePage = lazy(() => import('./pages/settlement/LedgerConsolePage'));
const PgReconciliationConsolePage = lazy(() => import('./pages/settlement/PgReconciliationConsolePage'));
const ChargebackConsolePage = lazy(() => import('./pages/settlement/ChargebackConsolePage'));
const RecoveryConsolePage = lazy(() => import('./pages/settlement/RecoveryConsolePage'));
const MonthlyClosingConsolePage = lazy(() => import('./pages/settlement/MonthlyClosingConsolePage'));
const TaxConsolePage = lazy(() => import('./pages/settlement/TaxConsolePage'));
const CommissionRateConsolePage = lazy(() => import('./pages/settlement/CommissionRateConsolePage'));

// 인쇄 전용 (레이아웃 없이 문서만 그리는 화면 — 새 창으로 열린다)
const SettlementPrintPage = lazy(() => import('./pages/print/SettlementPrintPage'));

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

// ── 인쇄 전용 (/print/**) ────────────────────────────────────────────────
// 종이에 나갈 문서만 그리므로 헤더·사이드바(Layout)를 걷어낸다. 권한은 화면과 동일하게
// ADMIN·MANAGER 로 막되, 미인증이면 관리자 로그인으로 보낸다.
const PrintRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const user = authApi.getCurrentUser();
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/admin/login" replace />;
  }
  if (user?.role !== 'ADMIN' && user?.role !== 'MANAGER') {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
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
      <AuthProvider>
      <MenuProvider>
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
            {/* 국민연금 사업장 인원·연봉 비교 — 내부 운영자(ADMIN·MANAGER) 전용 */}
            <Route path="/workforce"          element={<AdminManagerRoute><WorkforcePage /></AdminManagerRoute>} />
            {/* 한국은행 ECOS 경제지표 — 공공 데이터라 공개. 모든 사용자가 헤더 메뉴로 접근·왕복하도록 Layout 유지 */}
            <Route path="/economics"          element={<Layout><EconomicsPage /></Layout>} />

            {/* ── 일반 사용자 (USER + 인증) ── */}
            <Route path="/order"        element={<ProtectedRoute><OrderPage /></ProtectedRoute>} />
            <Route path="/recommend"    element={<ProtectedRoute><RecommendPage /></ProtectedRoute>} />
            <Route path="/cart"         element={<ProtectedRoute><CartPage /></ProtectedRoute>} />
            <Route path="/mypage"       element={<ProtectedRoute><MyPage /></ProtectedRoute>} />
            <Route path="/loans"        element={<ProtectedRoute><LoanPage /></ProtectedRoute>} />
            {/* AI 챗봇 (ai-service) — LLM 비용이 들어 인증 필수(USER 이상), 역할 무관 */}
            <Route path="/ai/chat"      element={<ProtectedRoute><AiChatPage /></ProtectedRoute>} />
            <Route path="/order/toss/success" element={<ProtectedRoute><TossPaymentSuccess /></ProtectedRoute>} />

            {/* ── 관리자·매니저 공용 ── */}
            <Route path="/admin"              element={<AdminManagerRoute><AdminDashboardPage /></AdminManagerRoute>} />
            {/* 정산 그룹 — 좌측 사이드바 항목은 menus 테이블이 정한다(SideNavLayout 이 트리에서 그림) */}
            <Route path="/product"            element={<AdminManagerRoute><SideNavLayout><ProductPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/settlement"   element={<AdminManagerRoute><SideNavLayout><SettlementAdmin /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/settlement/search"   element={<AdminManagerRoute><SideNavLayout><SettlementDashboard /></SideNavLayout></AdminManagerRoute>} />
            {/* 지급 콘솔 — 실자금 송금이라 /admin/payouts/** 는 서버가 ADMIN 전용으로 게이트한다.
                MANAGER 에게 화면만 열어 주면 전부 403 이 되므로 라우트도 AdminOnlyRoute 로 맞춘다. */}
            <Route path="/admin/payouts"      element={<AdminOnlyRoute><SideNavLayout><PayoutAdminPage /></SideNavLayout></AdminOnlyRoute>} />

            {/* ── 정산운영 콘솔 (ADMIN·MANAGER) ──
                URL 이 /admin/settlement/** 아래인 이유: nginx SPA 폴백이 /admin 하위에서
                (system|operation|ceo|settlement|login) 만 index.html 로 내려보낸다. 다른 접두사를
                쓰면 새로고침·직접진입이 404 가 된다. API 는 /admin/integrity 처럼 별도 접두사라 충돌 없음. */}
            <Route path="/admin/settlement/integrity"
              element={<AdminManagerRoute><SideNavLayout><IntegrityConsolePage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/settlement/reconciliation"
              element={<AdminManagerRoute><SideNavLayout><ReconciliationConsolePage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/settlement/pg-reconciliation"
              element={<AdminManagerRoute><SideNavLayout><PgReconciliationConsolePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 차지백은 서버가 /admin/chargebacks/** 를 ADMIN 전용으로 막는다 — 라우트도 같은 등급으로 */}
            <Route path="/admin/settlement/chargebacks"
              element={<AdminOnlyRoute><SideNavLayout><ChargebackConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/settlement/recoveries"
              element={<AdminManagerRoute><SideNavLayout><RecoveryConsolePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 월마감은 서버가 /admin/monthly-closing/** 를 ADMIN 전용으로 막는다 */}
            <Route path="/admin/settlement/monthly-closing"
              element={<AdminOnlyRoute><SideNavLayout><MonthlyClosingConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 요율은 정산 금액을 직접 바꾼다 — 서버가 /admin/commission-rates/** 를 ADMIN 으로만 막는다 */}
            <Route path="/admin/settlement/commission-rates"
              element={<AdminOnlyRoute><SideNavLayout><CommissionRateConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 세무는 서버가 /admin/tax/** · /admin/seller-tax-profiles/** 를 ADMIN·MANAGER 로 막는다 */}
            <Route path="/admin/settlement/tax"
              element={<AdminManagerRoute><SideNavLayout><TaxConsolePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 원장 조회는 ADMIN·MANAGER, 시산표·기간 마감은 ADMIN 전용이라 화면 안에서 역할로 가른다
                (라우트를 ADMIN 으로 잠그면 MANAGER 가 분개 조회조차 못 한다) */}
            <Route path="/admin/settlement/ledger"
              element={<AdminManagerRoute><SideNavLayout><LedgerConsolePage /></SideNavLayout></AdminManagerRoute>} />

            {/* 배송 관리 — 주문별 배송 생성·출고·상태 전이 (ShippingController) */}
            <Route path="/admin/shipping"     element={<AdminManagerRoute><ShippingAdminPage /></AdminManagerRoute>} />
            {/* 취소·환불 승인 큐 — 사용자가 신청한 건을 운영자가 종단으로 보낸다 */}
            <Route path="/admin/approvals"    element={<AdminManagerRoute><OrderApprovalPage /></AdminManagerRoute>} />
            <Route path="/tags"               element={<AdminManagerRoute><TagManagementPage /></AdminManagerRoute>} />

            {/* ── 인쇄 전용 문서 (새 창) ── */}
            <Route path="/print/settlement/:id"
              element={<PrintRoute><SettlementPrintPage /></PrintRoute>} />

            {/* ── 최고 관리자 전용: 운영 관제 — 시스템 관리(운영관리)로 편입, 구 경로는 리다이렉트 ── */}
            <Route path="/admin/operation"
              element={<Navigate to="/admin/system/operation" replace />} />

            {/* ── 최고 관리자 전용: 시스템 관리 (좌측 사이드바) ── */}
            <Route path="/admin/system"
              element={<Navigate to="/admin/system/menus" replace />} />
            <Route path="/admin/system/menus"
              element={<AdminOnlyRoute><SideNavLayout><MenuManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/codes"
              element={<AdminOnlyRoute><SideNavLayout><CommonCodeManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/rbac"
              element={<AdminOnlyRoute><SideNavLayout><RbacManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/ecommerce-categories"
              element={<AdminOnlyRoute><SideNavLayout><EcommerceCategoryAdmin /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/display-sections"
              element={<AdminOnlyRoute><SideNavLayout><DisplaySectionAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/option-catalog"
              element={<AdminOnlyRoute><SideNavLayout><OptionCatalogAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/proof-review"
              element={<AdminOnlyRoute><SideNavLayout><ProofReviewQueuePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/operation"
              element={<AdminOnlyRoute><SideNavLayout><OperationConsolePage /></SideNavLayout></AdminOnlyRoute>} />

            {/* ── CEO 인사이트 (ADMIN·MANAGER, 좌측 사이드바) — 위성 조회 서비스 묶음 ── */}
            <Route path="/admin/ceo"
              element={<Navigate to="/admin/ceo/insight" replace />} />
            <Route path="/admin/ceo/insight"
              element={<AdminManagerRoute><SideNavLayout><CeoInsightPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/economics"
              element={<AdminManagerRoute><SideNavLayout><EconomicsPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/financials"
              element={<AdminManagerRoute><SideNavLayout><FinancialStatementsPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/companies"
              element={<AdminManagerRoute><SideNavLayout><CompanyLookupPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/workforce"
              element={<AdminManagerRoute><SideNavLayout><WorkforcePage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/invest"
              element={<AdminManagerRoute><SideNavLayout><CeoInvestPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/invest-recommend"
              element={<AdminManagerRoute><SideNavLayout><CeoInvestRecommendPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/loans"
              element={<AdminManagerRoute><SideNavLayout><LoanPage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/loan-guide"
              element={<AdminManagerRoute><SideNavLayout><CeoLoanGuidePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 대출 심사·상환 안내 — 심사 절차·신용평가 산식·상환 구조 (정적 콘텐츠, API 없음) */}
            <Route path="/admin/ceo/loan-process"
              element={<AdminManagerRoute><SideNavLayout><CeoLoanProcessGuidePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 대출기관 안내 — 은행·저축은행·캐피탈·대부업 업권 비교 (정적 콘텐츠, API 없음) */}
            <Route path="/admin/ceo/lender-guide"
              element={<AdminManagerRoute><SideNavLayout><CeoLenderGuidePage /></SideNavLayout></AdminManagerRoute>} />
            {/* 자산운용펀드 안내 — 집합투자기구 6종 + 부동산·주식·채권 (정적 콘텐츠, API 없음) */}
            <Route path="/admin/ceo/fund-guide"
              element={<AdminManagerRoute><SideNavLayout><CeoFundGuidePage /></SideNavLayout></AdminManagerRoute>} />
            <Route path="/admin/ceo/accounts"
              element={<AdminManagerRoute><SideNavLayout><CeoAccountPage /></SideNavLayout></AdminManagerRoute>} />

          </Routes>
          </Suspense>
        </BrowserRouter>
      </CartProvider>
      </MenuProvider>
      </AuthProvider>
    </ToastProvider>
  );
}

export default App;
