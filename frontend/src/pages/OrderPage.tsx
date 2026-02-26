import React, { useState, useEffect } from 'react';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { productApi } from '@/api/product';
import { OrderResponse, PaymentResponse, ProductResponse } from '@/types';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const PRODUCTS_PER_PAGE = 5;
const TOSS_CLIENT_KEY = import.meta.env.VITE_TOSS_CLIENT_KEY as string;

const loadTossScript = (): Promise<void> =>
  new Promise((resolve, reject) => {
    if ((window as any).TossPayments) { resolve(); return; }
    const script = document.createElement('script');
    script.src = 'https://js.tosspayments.com/v1/payment';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('토스 스크립트 로드 실패'));
    document.head.appendChild(script);
  });

/* ─────────────────────────────────────────
   주문 상품 탭 - 주문 목록
───────────────────────────────────────── */
const OrderListTab: React.FC = () => {
  const userId = 1;
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [products, setProducts] = useState<Map<number, ProductResponse>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = async () => {
      try {
        const [orderList, productList] = await Promise.all([
          orderApi.getUserOrders(userId),
          productApi.getAllProducts(),
        ]);
        const productMap = new Map(productList.map((p) => [p.id, p]));
        setOrders(orderList.sort((a, b) => b.id - a.id));
        setProducts(productMap);
      } catch {
        setError('주문 목록을 불러오지 못했습니다.');
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const formatCurrency = (v: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

  const formatDate = (s: string) =>
    new Date(s).toLocaleDateString('ko-KR', {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });

  const statusConfig: Record<string, { label: string; cls: string }> = {
    CREATED:   { label: '주문 완료',   cls: 'bg-yellow-100 text-yellow-800' },
    PAID:      { label: '결제 완료',   cls: 'bg-green-100 text-green-800' },
    CANCELED:  { label: '취소됨',      cls: 'bg-red-100 text-red-800' },
    REFUNDED:  { label: '환불됨',      cls: 'bg-purple-100 text-purple-800' },
  };

  if (loading) return <Spinner size="md" message="주문 목록 불러오는 중..." />;
  if (error) return <p className="text-center text-red-600 py-8">{error}</p>;
  if (orders.length === 0)
    return (
      <div className="text-center py-16 text-gray-400">
        <svg className="mx-auto h-12 w-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
            d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z" />
        </svg>
        <p className="text-sm">주문 내역이 없습니다.</p>
      </div>
    );

  return (
    <div className="space-y-4">
      {orders.map((order) => {
        const product = order.productId ? products.get(order.productId) : null;
        const status = statusConfig[order.status] ?? { label: order.status, cls: 'bg-gray-100 text-gray-700' };
        return (
          <div key={order.id} className="bg-white rounded-xl border border-gray-200 p-5 shadow-sm">
            <div className="flex items-start justify-between mb-3">
              <div className="flex items-center gap-3">
                {product?.primaryImageUrl ? (
                  <img src={product.primaryImageUrl} alt={product.name}
                    className="w-12 h-12 rounded-lg object-cover flex-shrink-0" />
                ) : (
                  <div className="w-12 h-12 rounded-lg bg-gray-100 flex items-center justify-center flex-shrink-0">
                    <svg className="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                        d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
                    </svg>
                  </div>
                )}
                <div>
                  <p className="font-semibold text-gray-900">
                    {product ? product.name : `상품 #${order.productId ?? '-'}`}
                  </p>
                  <p className="text-xs text-gray-500 mt-0.5">주문 #{order.id}</p>
                </div>
              </div>
              <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${status.cls}`}>
                {status.label}
              </span>
            </div>

            <div className="flex justify-between items-center pt-3 border-t border-gray-100">
              <span className="text-xs text-gray-400">{formatDate(order.createdAt)}</span>
              <span className="text-base font-bold text-blue-700">{formatCurrency(order.amount)}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

/* ─────────────────────────────────────────
   주문하기 탭 - 주문 폼
───────────────────────────────────────── */
const OrderFormTab: React.FC = () => {
  const userId = 1;

  const [searchQuery, setSearchQuery] = useState('');
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState<ProductResponse | null>(null);

  const [paymentMethod, setPaymentMethod] = useState('CARD');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [step, setStep] = useState<'input' | 'order-created' | 'payment-ready' | 'completed'>('input');

  useEffect(() => {
    setLoadingProducts(true);
    productApi.getAvailableProducts()
      .then(setProducts)
      .catch(() => setError('상품 목록을 불러오지 못했습니다.'))
      .finally(() => setLoadingProducts(false));
  }, []);

  const filteredProducts = products
    .filter((p) => p.name.toLowerCase().includes(searchQuery.toLowerCase()))
    .slice(0, PRODUCTS_PER_PAGE);

  const handleCreateOrder = async () => {
    if (!selectedProduct) { setError('상품을 선택해주세요.'); return; }
    setLoading(true);
    setError(null);
    try {
      const orderRes = await orderApi.createOrder({
        userId,
        productId: selectedProduct.id,
        amount: selectedProduct.price,
      });
      setOrder(orderRes);

      if (paymentMethod === 'TOSS_PAYMENTS') {
        await handleTossPayment(orderRes);
      } else {
        setStep('order-created');
        setLoading(false);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || '주문 생성에 실패했습니다.');
      setLoading(false);
    }
  };

  const handleTossPayment = async (orderRes: OrderResponse) => {
    try {
      await loadTossScript();
      const tossPayments = (window as any).TossPayments(TOSS_CLIENT_KEY);
      const tossOrderId = `ORDER-${orderRes.id}-${Date.now()}`;

      await tossPayments.requestPayment('카드', {
        amount: Math.round(orderRes.amount),
        orderId: tossOrderId,
        orderName: selectedProduct!.name,
        customerName: '테스트 고객',
        successUrl: `${window.location.origin}/order/toss/success?dbOrderId=${orderRes.id}`,
        failUrl: `${window.location.origin}/order/toss/fail`,
      });
      // 페이지가 리다이렉트되므로 이 이후 코드는 실행되지 않음
    } catch (err: any) {
      setError(err.message || '토스페이먼츠 결제창을 열 수 없습니다.');
      setLoading(false);
    }
  };

  const handleCreatePayment = async () => {
    if (!order) return;
    setLoading(true);
    setError(null);
    try {
      const paymentRes = await paymentApi.createPayment({ orderId: order.id, paymentMethod });
      setPayment(paymentRes);
      setStep('payment-ready');
    } catch (err: any) {
      setError(err.response?.data?.message || '결제 생성에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleAuthorizePayment = async () => {
    if (!payment) return;
    setLoading(true);
    setError(null);
    try {
      const authorized = await paymentApi.authorizePayment(payment.id);
      setPayment(authorized);
      setTimeout(() => handleCapturePayment(authorized.id), 1000);
    } catch (err: any) {
      setError(err.response?.data?.message || '결제 승인에 실패했습니다.');
      setLoading(false);
    }
  };

  const handleCapturePayment = async (paymentId: number) => {
    try {
      const captured = await paymentApi.capturePayment(paymentId);
      setPayment(captured);
      setStep('completed');
    } catch (err: any) {
      setError(err.response?.data?.message || '결제 확정에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleNewOrder = () => {
    setSearchQuery('');
    setSelectedProduct(null);
    setPaymentMethod('CARD');
    setOrder(null);
    setPayment(null);
    setError(null);
    setStep('input');
  };

  const fmt = (v: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

  const statusBadge = (s: string) => ({
    CREATED: 'bg-yellow-100 text-yellow-800',
    PAID: 'bg-green-100 text-green-800',
    CANCELED: 'bg-red-100 text-red-800',
    READY: 'bg-blue-100 text-blue-800',
    AUTHORIZED: 'bg-indigo-100 text-indigo-800',
    CAPTURED: 'bg-green-100 text-green-800',
  }[s] ?? 'bg-gray-100 text-gray-800');

  return (
    <>
      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3">
          <p className="text-red-800 text-sm">{error}</p>
        </div>
      )}

      {/* Step 1: 주문 정보 입력 */}
      {step === 'input' && (
        <Card title="상품 선택 및 결제">
          <div className="space-y-5">

            {/* 상품 검색 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">상품 검색</label>
              <input
                type="text"
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                placeholder="상품명을 입력하세요"
                value={searchQuery}
                onChange={(e) => { setSearchQuery(e.target.value); setSelectedProduct(null); }}
              />
            </div>

            {/* 상품 목록 */}
            <div>
              {loadingProducts ? (
                <Spinner size="sm" message="상품 불러오는 중..." />
              ) : filteredProducts.length === 0 ? (
                <p className="text-sm text-gray-400 text-center py-6">
                  {searchQuery ? '검색 결과가 없습니다.' : '판매 가능한 상품이 없습니다.'}
                </p>
              ) : (
                <ul className="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
                  {filteredProducts.map((product) => {
                    const isSelected = selectedProduct?.id === product.id;
                    return (
                      <li
                        key={product.id}
                        onClick={() => setSelectedProduct(product)}
                        className={`flex items-center justify-between px-4 py-3 cursor-pointer transition-colors ${
                          isSelected ? 'bg-blue-50 border-l-4 border-blue-500' : 'hover:bg-gray-50'
                        }`}
                      >
                        <div className="flex items-center gap-3">
                          {product.primaryImageUrl ? (
                            <img src={product.primaryImageUrl} alt={product.name}
                              className="w-10 h-10 rounded object-cover flex-shrink-0" />
                          ) : (
                            <div className="w-10 h-10 rounded bg-gray-100 flex items-center justify-center flex-shrink-0">
                              <span className="text-gray-400 text-xs">없음</span>
                            </div>
                          )}
                          <div>
                            <p className="text-sm font-medium text-gray-900">{product.name}</p>
                            {product.description && (
                              <p className="text-xs text-gray-400 truncate max-w-xs">{product.description}</p>
                            )}
                          </div>
                        </div>
                        <div className="text-right flex-shrink-0">
                          <p className="text-sm font-semibold text-gray-900">{fmt(product.price)}</p>
                          <p className="text-xs text-gray-400">재고 {product.stockQuantity}개</p>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
              {products.length > 0 && (
                <p className="text-xs text-gray-400 mt-1 text-right">
                  최대 {PRODUCTS_PER_PAGE}개 표시 · 전체 {products.length}개
                </p>
              )}
            </div>

            {/* 선택 상품 요약 */}
            {selectedProduct && (
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-3.5">
                <p className="text-xs font-medium text-blue-600 mb-1">선택된 상품</p>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-800">{selectedProduct.name}</span>
                  <span className="font-bold text-blue-700">{fmt(selectedProduct.price)}</span>
                </div>
              </div>
            )}

            {/* 결제 수단 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">결제 수단</label>
              <select
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value)}
              >
                <option value="CARD" className="text-gray-900">신용카드</option>
                <option value="BANK_TRANSFER" className="text-gray-900">계좌이체</option>
                <option value="VIRTUAL_ACCOUNT" className="text-gray-900">가상계좌</option>
                <option value="TOSS_PAYMENTS" className="text-gray-900">토스페이먼츠</option>
              </select>
            </div>

            {/* 토스페이먼츠 안내 */}
            {paymentMethod === 'TOSS_PAYMENTS' && selectedProduct && (
              <div className="flex items-center gap-2 bg-sky-50 border border-sky-200 rounded-lg p-3 text-sm text-sky-800">
                <svg className="w-4 h-4 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                </svg>
                <span>주문하기를 누르면 토스페이먼츠 결제창이 열립니다.</span>
              </div>
            )}

            {loading ? (
              <Spinner size="md" message="주문 생성 중..." />
            ) : (
              <button
                onClick={handleCreateOrder}
                disabled={!selectedProduct}
                className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                {selectedProduct ? '주문하기' : '상품을 먼저 선택해주세요'}
              </button>
            )}
          </div>
        </Card>
      )}

      {/* Step 2: 주문 생성 완료 */}
      {step === 'order-created' && order && (
        <Card title="주문이 생성되었습니다">
          <div className="space-y-3 mb-6">
            {selectedProduct && (
              <div className="flex justify-between py-2.5 border-b">
                <span className="text-gray-500 text-sm">상품명</span>
                <span className="font-semibold text-sm">{selectedProduct.name}</span>
              </div>
            )}
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 번호</span>
              <span className="font-semibold text-sm">#{order.id}</span>
            </div>
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 금액</span>
              <span className="font-bold">{fmt(order.amount)}</span>
            </div>
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 상태</span>
              <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${statusBadge(order.status)}`}>
                {order.status}
              </span>
            </div>
          </div>
          {loading ? <Spinner size="md" message="결제 정보 생성 중..." /> : (
            <button onClick={handleCreatePayment}
              className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
              결제 진행하기
            </button>
          )}
        </Card>
      )}

      {/* Step 3: 결제 준비 */}
      {step === 'payment-ready' && payment && (
        <Card title="결제 정보">
          <div className="space-y-3 mb-6">
            {[
              { label: '결제 번호', value: `#${payment.id}` },
              { label: '결제 금액', value: fmt(payment.amount), bold: true },
              { label: '결제 수단', value: payment.paymentMethod },
            ].map(({ label, value, bold }) => (
              <div key={label} className="flex justify-between py-2.5 border-b">
                <span className="text-gray-500 text-sm">{label}</span>
                <span className={bold ? 'font-bold' : 'font-semibold text-sm'}>{value}</span>
              </div>
            ))}
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">결제 상태</span>
              <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${statusBadge(payment.status)}`}>
                {payment.status}
              </span>
            </div>
          </div>
          {loading ? <Spinner size="md" message="결제 처리 중..." /> : (
            <button onClick={handleAuthorizePayment}
              className="w-full bg-green-600 text-white py-3 rounded-lg font-semibold hover:bg-green-700 transition-colors">
              결제하기
            </button>
          )}
        </Card>
      )}

      {/* Step 4: 결제 완료 */}
      {step === 'completed' && payment && order && (
        <Card>
          <div className="text-center mb-6">
            <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100 mb-4">
              <svg className="h-9 w-9 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h2 className="text-xl font-bold text-gray-900 mb-1">결제가 완료되었습니다!</h2>
            <p className="text-sm text-gray-500">정상적으로 처리되었습니다.</p>
          </div>
          <div className="bg-gray-50 rounded-xl p-5 mb-6 space-y-3">
            {selectedProduct && (
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">상품명</span>
                <span className="font-semibold">{selectedProduct.name}</span>
              </div>
            )}
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">주문 번호</span>
              <span className="font-semibold">#{order.id}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">결제 번호</span>
              <span className="font-semibold">#{payment.id}</span>
            </div>
            {payment.pgTransactionId && (
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">PG 거래 ID</span>
                <span className="font-mono text-xs text-gray-600 max-w-[180px] truncate">{payment.pgTransactionId}</span>
              </div>
            )}
            <div className="flex justify-between items-center pt-3 border-t border-gray-200">
              <span className="font-medium text-gray-900">결제 금액</span>
              <span className="text-xl font-bold text-green-600">{fmt(payment.amount)}</span>
            </div>
          </div>
          <button onClick={handleNewOrder}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
            새로운 주문하기
          </button>
        </Card>
      )}
    </>
  );
};

/* ─────────────────────────────────────────
   메인 OrderPage - 탭 컨테이너
───────────────────────────────────────── */
type TabId = 'order' | 'history';

const TABS: { id: TabId; label: string }[] = [
  { id: 'order',   label: '주문하기' },
  { id: 'history', label: '주문 상품' },
];

const OrderPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabId>('order');

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-2xl mx-auto">

        {/* 헤더 */}
        <div className="text-center mb-6">
          <h1 className="text-3xl font-bold text-gray-900">주문 & 결제</h1>
        </div>

        {/* 탭 */}
        <div className="flex bg-white rounded-xl shadow-sm border border-gray-200 mb-6 p-1">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 py-2.5 text-sm font-semibold rounded-lg transition-all ${
                activeTab === tab.id
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-gray-500 hover:text-gray-800'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* 탭 콘텐츠 */}
        {activeTab === 'order'   && <OrderFormTab />}
        {activeTab === 'history' && <OrderListTab />}
      </div>
    </div>
  );
};

export default OrderPage;