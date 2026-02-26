import React, { useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { paymentApi } from '@/api/payment';
import { PaymentResponse } from '@/types';
import Spinner from '@/components/Spinner';

const TossPaymentSuccess: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const paymentKey  = searchParams.get('paymentKey');
    const tossOrderId = searchParams.get('orderId');
    const amountStr   = searchParams.get('amount');
    const dbOrderId   = searchParams.get('dbOrderId');

    if (!paymentKey || !tossOrderId || !amountStr || !dbOrderId) {
      setError('결제 정보가 올바르지 않습니다.');
      setLoading(false);
      return;
    }

    paymentApi
      .confirmTossPayment({
        dbOrderId: Number(dbOrderId),
        paymentKey,
        tossOrderId,
        amount: Number(amountStr),
      })
      .then((res) => setPayment(res))
      .catch((err) => {
        setError(err.response?.data?.message || '결제 확인 중 오류가 발생했습니다.');
      })
      .finally(() => setLoading(false));
  }, []);

  const formatCurrency = (value: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(value);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-50">
        <Spinner size="lg" message="결제 확인 중..." />
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-red-50 to-pink-50 px-4">
        <div className="bg-white rounded-2xl shadow-lg p-8 max-w-md w-full text-center">
          <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-red-100 mb-4">
            <svg className="h-10 w-10 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </div>
          <h2 className="text-xl font-bold text-gray-900 mb-2">결제 확인 실패</h2>
          <p className="text-gray-600 mb-6">{error}</p>
          <button
            onClick={() => navigate('/order')}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors"
          >
            주문 페이지로 돌아가기
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50 to-emerald-50 px-4">
      <div className="bg-white rounded-2xl shadow-lg p-8 max-w-md w-full">
        <div className="text-center mb-6">
          <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100 mb-4">
            <svg className="h-10 w-10 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h2 className="text-2xl font-bold text-gray-900 mb-1">결제 완료!</h2>
          <p className="text-gray-500 text-sm">토스페이먼츠 결제가 성공적으로 처리되었습니다.</p>
        </div>

        {payment && (
          <div className="bg-gray-50 rounded-xl p-5 mb-6 space-y-3">
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">결제 번호</span>
              <span className="font-semibold text-gray-900">#{payment.id}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">결제 수단</span>
              <span className="font-semibold text-gray-900">토스페이먼츠</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">PG 거래 ID</span>
              <span className="font-mono text-xs text-gray-700 truncate max-w-[180px]">{payment.pgTransactionId}</span>
            </div>
            <div className="flex justify-between items-center pt-3 border-t border-gray-200">
              <span className="text-gray-900 font-medium">결제 금액</span>
              <span className="text-2xl font-bold text-green-600">{formatCurrency(payment.amount)}</span>
            </div>
          </div>
        )}

        <button
          onClick={() => navigate('/order')}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors"
        >
          새로운 주문하기
        </button>
      </div>
    </div>
  );
};

export default TossPaymentSuccess;