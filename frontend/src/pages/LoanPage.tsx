import React, { useState } from 'react';
import { loanApi, LoanResponse } from '@/api/loan';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const statusBadge = (s: string) => ({
  REQUESTED: 'bg-yellow-100 text-yellow-800',
  DISBURSED: 'bg-blue-100 text-blue-800',
  REPAYING:  'bg-indigo-100 text-indigo-800',
  REPAID:    'bg-green-100 text-green-800',
  OVERDUE:   'bg-red-100 text-red-800',
  CANCELED:  'bg-gray-200 text-gray-700',
}[s] ?? 'bg-gray-100 text-gray-800');

const LoanPage: React.FC = () => {
  // 신청 폼
  const [sellerId, setSellerId] = useState<number>(1);
  const [principal, setPrincipal] = useState<number>(1000000);
  const [financingDays, setFinancingDays] = useState<number>(7);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 목록
  const [querySellerId, setQuerySellerId] = useState<number>(1);
  const [loans, setLoans] = useState<LoanResponse[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const loadLoans = async (sid: number) => {
    setLoadingList(true);
    setError(null);
    try {
      setLoans(await loanApi.bySeller(sid));
    } catch (err: any) {
      setError(err.response?.data?.message || '대출 목록 조회에 실패했습니다.');
    } finally {
      setLoadingList(false);
    }
  };

  const handleRequest = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const created = await loanApi.request({ sellerId, principal, financingDays });
      setNotice(`대출 신청 완료 — #${created.id} (수수료 ${fmt(created.fee)}, 미상환 ${fmt(created.outstanding)})`);
      setQuerySellerId(sellerId);
      await loadLoans(sellerId);
    } catch (err: any) {
      setError(err.response?.data?.message || '대출 신청에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDisburse = async (id: number) => {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await loanApi.disburse(id);
      setNotice(`#${id} 대출 실행(선지급) 완료`);
      await loadLoans(querySellerId);
    } catch (err: any) {
      setError(err.response?.data?.message || '대출 실행에 실패했습니다.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-emerald-50 to-teal-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto space-y-6">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">대출하기</h1>
          <p className="mt-1 text-sm text-gray-500">선정산 대출 — 셀러의 미확정 정산금을 담보로 선지급</p>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-3">
            <p className="text-red-800 text-sm">{error}</p>
          </div>
        )}
        {notice && (
          <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-3">
            <p className="text-emerald-800 text-sm">{notice}</p>
          </div>
        )}

        {/* 대출 신청 */}
        <Card title="선정산 대출 신청">
          <form className="space-y-4" onSubmit={handleRequest}>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">셀러 ID</label>
                <input type="number" min={1} required value={sellerId}
                  onChange={(e) => setSellerId(Number(e.target.value))}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">대출 원금(원)</label>
                <input type="number" min={1} step="any" required value={principal}
                  onChange={(e) => setPrincipal(Number(e.target.value))}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">선지급 일수</label>
                <input type="number" min={0} required value={financingDays}
                  onChange={(e) => setFinancingDays(Number(e.target.value))}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
              </div>
            </div>
            {submitting ? <Spinner size="sm" message="신청 중..." /> : (
              <button type="submit"
                className="w-full bg-emerald-600 text-white py-3 rounded-lg font-semibold hover:bg-emerald-700 transition-colors">
                대출 신청
              </button>
            )}
          </form>
        </Card>

        {/* 셀러별 조회 */}
        <Card title="대출 현황 조회">
          <div className="flex items-end gap-3 mb-4">
            <div className="flex-1">
              <label className="block text-sm font-medium text-gray-700 mb-1.5">셀러 ID</label>
              <input type="number" min={1} value={querySellerId}
                onChange={(e) => setQuerySellerId(Number(e.target.value))}
                className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
            </div>
            <button onClick={() => loadLoans(querySellerId)}
              className="px-5 py-2.5 bg-gray-800 text-white rounded-lg text-sm font-semibold hover:bg-gray-900 transition-colors">
              조회
            </button>
          </div>

          {loadingList ? <Spinner size="md" message="조회 중..." /> : loans.length === 0 ? (
            <p className="text-sm text-gray-400 text-center py-6">조회된 대출이 없습니다. 위에서 신청하거나 셀러 ID로 조회하세요.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-gray-500 border-b">
                    <th className="py-2 px-2">#</th>
                    <th className="py-2 px-2">원금</th>
                    <th className="py-2 px-2">수수료</th>
                    <th className="py-2 px-2">미상환</th>
                    <th className="py-2 px-2">상태</th>
                    <th className="py-2 px-2 text-right">액션</th>
                  </tr>
                </thead>
                <tbody>
                  {loans.map((l) => (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="py-2.5 px-2 font-medium">#{l.id}</td>
                      <td className="py-2.5 px-2">{fmt(l.principal)}</td>
                      <td className="py-2.5 px-2 text-gray-600">{fmt(l.fee)}</td>
                      <td className="py-2.5 px-2 font-semibold">{fmt(l.outstanding)}</td>
                      <td className="py-2.5 px-2">
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${statusBadge(l.status)}`}>
                          {l.status}
                        </span>
                      </td>
                      <td className="py-2.5 px-2 text-right">
                        <button
                          disabled={busyId === l.id}
                          onClick={() => handleDisburse(l.id)}
                          className="px-3 py-1.5 bg-emerald-600 text-white rounded-lg text-xs font-semibold hover:bg-emerald-700 transition-colors disabled:opacity-40"
                        >
                          {busyId === l.id ? '처리중...' : '실행(선지급)'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
};

export default LoanPage;
