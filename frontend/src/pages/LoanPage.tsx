import React, { useState } from 'react';
import {
  loanApi,
  LoanResponse,
  type CorporateCredit,
  type CorporateLoan,
} from '@/api/loan';
import { financialApi, type FinancialCompany, type FinancialCompanyPage } from '@/api/financial';
import { authApi } from '@/api/auth';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const pct = (v: number | null | undefined) =>
  v === null || v === undefined ? 'N/A' : `${v.toFixed(2)}%`;

const fmtDate = (v?: string | null) =>
  v ? new Date(v).toLocaleDateString('ko-KR') : '-';

const statusBadge = (s: string) => ({
  REQUESTED: 'bg-yellow-100 text-yellow-800',
  APPROVED:  'bg-blue-100 text-blue-800',
  DISBURSED: 'bg-blue-100 text-blue-800',
  REPAYING:  'bg-indigo-100 text-indigo-800',
  REPAID:    'bg-green-100 text-green-800',
  OVERDUE:   'bg-red-100 text-red-800',
  WRITTEN_OFF: 'bg-gray-800 text-white',
  REJECTED:  'bg-red-100 text-red-800',
  CANCELED:  'bg-gray-200 text-gray-700',
}[s] ?? 'bg-gray-100 text-gray-800');

const creditGradeClass = (grade: string) => ({
  A: 'bg-emerald-600 text-white',
  B: 'bg-lime-600 text-white',
  C: 'bg-amber-500 text-white',
  D: 'bg-orange-500 text-white',
  E: 'bg-red-600 text-white',
}[grade] ?? 'bg-gray-500 text-white');

type Tab = 'seller' | 'corporate';

// ── 셀러 선정산 대출 섹션 ─────────────────────────────────────────
const SellerLoanSection: React.FC = () => {
  const [sellerId, setSellerId] = useState<number>(1);
  const [principal, setPrincipal] = useState<number>(1000000);
  const [financingDays, setFinancingDays] = useState<number>(7);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [querySellerId, setQuerySellerId] = useState<number>(1);
  const [loans, setLoans] = useState<LoanResponse[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  // 연체·상각은 회수 담당자(ADMIN) 전용 조작 — JWT 역할로 노출 여부를 가른다.
  const isAdmin = authApi.getCurrentUser()?.role === 'ADMIN';

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

  const handleMarkOverdue = async (id: number) => {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await loanApi.markOverdue(id);
      setNotice(`#${id} 연체 처리 완료`);
      await loadLoans(querySellerId);
    } catch (err: any) {
      setError(err.response?.data?.message || '연체 처리에 실패했습니다.');
    } finally {
      setBusyId(null);
    }
  };

  const handleWriteOff = async (id: number) => {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await loanApi.writeOff(id);
      setNotice(`#${id} 상각(대손) 처리 완료`);
      await loadLoans(querySellerId);
    } catch (err: any) {
      setError(err.response?.data?.message || '상각 처리에 실패했습니다.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-6">
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
                  <th className="py-2 px-2">만기</th>
                  <th className="py-2 px-2">상태</th>
                  <th className="py-2 px-2 text-right">액션</th>
                </tr>
              </thead>
              <tbody>
                {loans.map((l) => {
                  const canDisburse = l.status === 'REQUESTED' || l.status === 'APPROVED';
                  const canOverdue = isAdmin && l.status === 'DISBURSED';
                  const canWriteOff = isAdmin && l.status === 'OVERDUE';
                  return (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="py-2.5 px-2 font-medium">#{l.id}</td>
                      <td className="py-2.5 px-2">{fmt(l.principal)}</td>
                      <td className="py-2.5 px-2 text-gray-600">{fmt(l.fee)}</td>
                      <td className="py-2.5 px-2 font-semibold">{fmt(l.outstanding)}</td>
                      <td className="py-2.5 px-2 text-gray-600">{fmtDate(l.dueAt)}</td>
                      <td className="py-2.5 px-2">
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${statusBadge(l.status)}`}>
                          {l.status}
                        </span>
                      </td>
                      <td className="py-2.5 px-2 text-right">
                        <div className="flex justify-end gap-1.5">
                          {canDisburse && (
                            <button
                              disabled={busyId === l.id}
                              onClick={() => handleDisburse(l.id)}
                              className="px-3 py-1.5 bg-emerald-600 text-white rounded-lg text-xs font-semibold hover:bg-emerald-700 transition-colors disabled:opacity-40"
                            >
                              {busyId === l.id ? '처리중...' : '실행(선지급)'}
                            </button>
                          )}
                          {canOverdue && (
                            <button
                              disabled={busyId === l.id}
                              onClick={() => handleMarkOverdue(l.id)}
                              className="px-3 py-1.5 bg-amber-600 text-white rounded-lg text-xs font-semibold hover:bg-amber-700 transition-colors disabled:opacity-40"
                            >
                              {busyId === l.id ? '처리중...' : '연체'}
                            </button>
                          )}
                          {canWriteOff && (
                            <button
                              disabled={busyId === l.id}
                              onClick={() => handleWriteOff(l.id)}
                              className="px-3 py-1.5 bg-red-600 text-white rounded-lg text-xs font-semibold hover:bg-red-700 transition-colors disabled:opacity-40"
                            >
                              {busyId === l.id ? '처리중...' : '상각'}
                            </button>
                          )}
                          {!canDisburse && !canOverdue && !canWriteOff && (
                            <span className="text-xs text-gray-400">-</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
};

// ── 기업대출 섹션 ─────────────────────────────────────────────────
const CorporateLoanSection: React.FC = () => {
  // 종목 검색
  const [keyword, setKeyword] = useState('');
  const [companies, setCompanies] = useState<FinancialCompanyPage | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<FinancialCompany | null>(null);

  // 신용평가
  const [credit, setCredit] = useState<CorporateCredit | null>(null);
  const [loadingCredit, setLoadingCredit] = useState(false);

  // 신청 폼
  const [principal, setPrincipal] = useState<number>(10_000_000);
  const [termDays, setTermDays] = useState<number>(30);
  const [submitting, setSubmitting] = useState(false);

  // 목록
  const [loans, setLoans] = useState<CorporateLoan[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  // 상환 입력(인라인)
  const [repayId, setRepayId] = useState<number | null>(null);
  const [repayAmount, setRepayAmount] = useState<number>(0);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    setSearching(true);
    setError(null);
    try {
      setCompanies(await financialApi.companies(keyword, 0, 10));
    } catch (err: any) {
      setError(err.response?.data?.message || '기업 검색에 실패했습니다.');
    } finally {
      setSearching(false);
    }
  };

  const selectCompany = async (company: FinancialCompany) => {
    setSelected(company);
    setCredit(null);
    setLoans([]);
    setRepayId(null);
    setLoadingCredit(true);
    setError(null);
    try {
      const [creditRes, loansRes] = await Promise.all([
        loanApi.corporateCredit(company.stockCode),
        loanApi.corporateByStock(company.stockCode).catch(() => []),
      ]);
      setCredit(creditRes);
      setLoans(loansRes);
    } catch (err: any) {
      setError(err.response?.data?.message || '기업 신용평가 조회에 실패했습니다.');
    } finally {
      setLoadingCredit(false);
    }
  };

  const overLimit = credit != null && principal > credit.limit;

  const handleRequest = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const created = await loanApi.requestCorporate({ stockCode: selected.stockCode, principal, termDays });
      setNotice(`기업대출 신청 완료 — #${created.id} (${created.creditGrade}, 수수료 ${fmt(created.fee)})`);
      setLoans(await loanApi.corporateByStock(selected.stockCode));
    } catch (err: any) {
      setError(err.response?.data?.message || '기업대출 신청에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDisburse = async (id: number) => {
    if (!selected) return;
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await loanApi.disburseCorporate(id);
      setNotice(`#${id} 기업대출 실행(선지급) 완료`);
      setLoans(await loanApi.corporateByStock(selected.stockCode));
    } catch (err: any) {
      setError(err.response?.data?.message || '기업대출 실행에 실패했습니다.');
    } finally {
      setBusyId(null);
    }
  };

  const handleRepay = async (id: number) => {
    if (!selected || repayAmount <= 0) return;
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await loanApi.repayCorporate(id, repayAmount);
      setNotice(`#${id} 기업대출 상환 완료 (${fmt(repayAmount)})`);
      setRepayId(null);
      setRepayAmount(0);
      setLoans(await loanApi.corporateByStock(selected.stockCode));
    } catch (err: any) {
      setError(err.response?.data?.message || '기업대출 상환에 실패했습니다.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-6">
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

      <Card title="종목 검색">
        <form onSubmit={handleSearch} className="flex gap-2">
          <input
            type="text"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="기업명 또는 종목코드"
            className="min-w-0 flex-1 px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
          />
          <button type="submit" className="px-5 py-2.5 bg-gray-800 text-white rounded-lg text-sm font-semibold hover:bg-gray-900 transition-colors">
            검색
          </button>
        </form>

        {searching ? (
          <div className="py-6"><Spinner size="sm" /></div>
        ) : companies && (
          <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-2">
            {companies.content.map((company) => (
              <button
                key={company.stockCode}
                onClick={() => selectCompany(company)}
                className={`rounded-md border px-4 py-3 text-left transition-colors ${
                  selected?.stockCode === company.stockCode
                    ? 'border-emerald-600 bg-emerald-50'
                    : 'border-gray-200 hover:border-emerald-400 hover:bg-gray-50'
                }`}
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="font-semibold text-gray-900">{company.name}</span>
                  <span className="font-mono text-xs text-gray-500">{company.stockCode}</span>
                </div>
                <p className="mt-1 text-xs text-gray-500">{company.market}</p>
              </button>
            ))}
            {companies.content.length === 0 && (
              <p className="col-span-full py-4 text-center text-sm text-gray-400">검색 결과가 없습니다.</p>
            )}
          </div>
        )}
      </Card>

      {loadingCredit && (
        <Card><div className="flex justify-center py-10"><Spinner /></div></Card>
      )}

      {credit && !loadingCredit && (
        <Card title="기업 신용평가">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <h3 className="text-xl font-bold text-gray-900">
                {credit.corpName}
                <span className="ml-2 font-mono text-sm font-medium text-gray-400">({credit.stockCode})</span>
              </h3>
              <p className="mt-1 text-sm text-gray-500">{credit.market} · {credit.fiscalYear} 기준</p>
            </div>
            <div className="flex items-center gap-3">
              <span className={`rounded-lg px-4 py-2 text-xl font-bold ${creditGradeClass(credit.creditGrade)}`}>{credit.creditGrade}</span>
              <div className="text-right">
                <p className="text-xs text-gray-500">신용점수</p>
                <p className="text-lg font-bold text-gray-900">{credit.creditScore}</p>
              </div>
            </div>
          </div>

          <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3">
              <p className="text-xs font-semibold text-gray-500">대출 한도</p>
              <p className="mt-1 text-lg font-bold text-gray-900">{fmt(credit.limit)}</p>
            </div>
            <div className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3">
              <p className="text-xs font-semibold text-gray-500">부채비율</p>
              <p className="mt-1 text-lg font-bold text-gray-900">{pct(credit.debtRatio)}</p>
            </div>
            <div className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3">
              <p className="text-xs font-semibold text-gray-500">영업이익률</p>
              <p className="mt-1 text-lg font-bold text-gray-900">{pct(credit.operatingMargin)}</p>
            </div>
            <div className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3">
              <p className="text-xs font-semibold text-gray-500">ROA</p>
              <p className="mt-1 text-lg font-bold text-gray-900">{pct(credit.roa)}</p>
            </div>
          </div>
          {credit.reputationGrade && (
            <p className="mt-3 text-xs text-gray-500">평판 등급: <span className="font-semibold text-gray-700">{credit.reputationGrade}</span></p>
          )}

          <form onSubmit={handleRequest} className="mt-6 space-y-4 border-t border-gray-100 pt-5">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">대출 원금(원)</label>
                <input type="number" min={1} step="any" required value={principal}
                  onChange={(e) => setPrincipal(Number(e.target.value))}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
                {overLimit && (
                  <p className="mt-1 text-xs font-semibold text-red-600">한도({fmt(credit.limit)})를 초과했습니다.</p>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">대출 기간(일)</label>
                <input type="number" min={1} required value={termDays}
                  onChange={(e) => setTermDays(Number(e.target.value))}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500" />
              </div>
            </div>
            {submitting ? <Spinner size="sm" message="신청 중..." /> : (
              <button type="submit"
                className="w-full bg-emerald-600 text-white py-3 rounded-lg font-semibold hover:bg-emerald-700 transition-colors">
                기업대출 신청
              </button>
            )}
          </form>
        </Card>
      )}

      {selected && (
        <Card title="기업대출 현황">
          {loans.length === 0 ? (
            <p className="text-sm text-gray-400 text-center py-6">해당 종목의 기업대출 내역이 없습니다.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-gray-500 border-b">
                    <th className="py-2 px-2">#</th>
                    <th className="py-2 px-2">원금</th>
                    <th className="py-2 px-2">수수료</th>
                    <th className="py-2 px-2">미상환</th>
                    <th className="py-2 px-2">기간</th>
                    <th className="py-2 px-2">등급</th>
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
                      <td className="py-2.5 px-2 text-gray-600">{l.termDays}일</td>
                      <td className="py-2.5 px-2">{l.creditGrade} ({l.creditScore})</td>
                      <td className="py-2.5 px-2">
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${statusBadge(l.status)}`}>
                          {l.status}
                        </span>
                      </td>
                      <td className="py-2.5 px-2 text-right">
                        {(l.status === 'REQUESTED' || l.status === 'APPROVED') ? (
                          <button
                            disabled={busyId === l.id}
                            onClick={() => handleDisburse(l.id)}
                            className="px-3 py-1.5 bg-emerald-600 text-white rounded-lg text-xs font-semibold hover:bg-emerald-700 transition-colors disabled:opacity-40"
                          >
                            {busyId === l.id ? '처리중...' : '실행(선지급)'}
                          </button>
                        ) : l.status === 'DISBURSED' ? (
                          repayId === l.id ? (
                            <div className="flex items-center justify-end gap-1.5">
                              <input
                                type="number"
                                min={1}
                                step="any"
                                value={repayAmount}
                                onChange={(e) => setRepayAmount(Number(e.target.value))}
                                className="w-28 px-2 py-1 border border-gray-300 rounded text-xs focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
                                placeholder="상환액"
                              />
                              <button
                                disabled={busyId === l.id || repayAmount <= 0}
                                onClick={() => handleRepay(l.id)}
                                className="px-2 py-1 bg-emerald-600 text-white rounded text-xs font-semibold hover:bg-emerald-700 transition-colors disabled:opacity-40"
                              >
                                {busyId === l.id ? '...' : '확인'}
                              </button>
                              <button
                                onClick={() => { setRepayId(null); setRepayAmount(0); }}
                                className="px-2 py-1 bg-gray-200 text-gray-700 rounded text-xs font-semibold hover:bg-gray-300 transition-colors"
                              >
                                취소
                              </button>
                            </div>
                          ) : (
                            <button
                              onClick={() => { setRepayId(l.id); setRepayAmount(l.outstanding); }}
                              className="px-3 py-1.5 bg-indigo-600 text-white rounded-lg text-xs font-semibold hover:bg-indigo-700 transition-colors"
                            >
                              상환
                            </button>
                          )
                        ) : (
                          <span className="text-xs text-gray-400">-</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </div>
  );
};

const LoanPage: React.FC = () => {
  const [tab, setTab] = useState<Tab>('seller');

  return (
    <div className="min-h-screen bg-gradient-to-br from-emerald-50 to-teal-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto space-y-6">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">대출하기</h1>
          <p className="mt-1 text-sm text-gray-500">
            {tab === 'seller' ? '선정산 대출 — 셀러의 미확정 정산금을 담보로 선지급' : '기업대출 — 코스피 상장사 신용평가 기반 대출'}
          </p>
        </div>

        <div className="flex justify-center gap-2">
          <button
            onClick={() => setTab('seller')}
            className={`px-5 py-2 rounded-lg text-sm font-semibold transition-colors ${
              tab === 'seller' ? 'bg-emerald-600 text-white' : 'bg-white text-gray-600 border border-gray-200 hover:bg-gray-50'
            }`}
          >
            셀러 선정산
          </button>
          <button
            onClick={() => setTab('corporate')}
            className={`px-5 py-2 rounded-lg text-sm font-semibold transition-colors ${
              tab === 'corporate' ? 'bg-emerald-600 text-white' : 'bg-white text-gray-600 border border-gray-200 hover:bg-gray-50'
            }`}
          >
            기업대출
          </button>
        </div>

        {tab === 'seller' ? <SellerLoanSection /> : <CorporateLoanSection />}
      </div>
    </div>
  );
};

export default LoanPage;
