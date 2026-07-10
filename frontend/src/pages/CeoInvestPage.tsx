import React, { useEffect, useState } from 'react';
import {
  investmentApi,
  type InvestmentAxisScore,
  type InvestmentFunding,
  type InvestmentGrade,
  type InvestmentOrder,
  type InvestmentScore,
} from '@/api/investment';
import { financialApi, type FinancialCompany, type FinancialCompanyPage } from '@/api/financial';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmtAmount = (value: number | null | undefined) => {
  if (value === null || value === undefined) return 'N/A';
  const abs = Math.abs(value);
  if (abs >= 1_0000_0000_0000) {
    return `${(value / 1_0000_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조`;
  }
  if (abs >= 1_0000_0000) {
    return `${(value / 1_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`;
  }
  return value.toLocaleString('ko-KR');
};

const fmtWon = (value: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(value);

const pct = (value: number | null | undefined) =>
  value === null || value === undefined ? 'N/A' : `${value.toFixed(2)}%`;

/** 등급별 뱃지 색상 (AAA 최상 → CCC 최하) */
const gradeClass = (grade: InvestmentGrade) => {
  switch (grade) {
    case 'AAA':
    case 'AA':
      return 'bg-emerald-600 text-white';
    case 'A':
    case 'BBB':
      return 'bg-lime-600 text-white';
    case 'BB':
    case 'B':
      return 'bg-amber-500 text-white';
    default:
      return 'bg-red-600 text-white';
  }
};

const orderStatusBadge = (status: InvestmentOrder['status']) =>
  ({
    REQUESTED: 'bg-yellow-100 text-yellow-800',
    APPROVED: 'bg-blue-100 text-blue-800',
    EXECUTED: 'bg-green-100 text-green-800',
    REJECTED: 'bg-red-100 text-red-800',
    CANCELED: 'bg-gray-200 text-gray-700',
  }[status] ?? 'bg-gray-100 text-gray-800');

interface AxisView {
  key: string;
  label: string;
  axis: InvestmentAxisScore;
  metrics: { label: string; value: string }[];
}

const buildAxes = (score: InvestmentScore): AxisView[] => [
  {
    key: 'profitability',
    label: '수익성',
    axis: score.profitability,
    metrics: [
      { label: '영업이익률', value: pct(score.profitability.operatingMargin) },
      { label: 'ROA', value: pct(score.profitability.roa) },
    ],
  },
  {
    key: 'stability',
    label: '안정성',
    axis: score.stability,
    metrics: [
      { label: '부채비율', value: pct(score.stability.debtRatio) },
      { label: '자기자본비율', value: pct(score.stability.equityRatio) },
    ],
  },
  {
    key: 'growth',
    label: '성장성',
    axis: score.growth,
    metrics: [
      { label: '매출성장률', value: pct(score.growth.revenueGrowth) },
      { label: '순이익성장률', value: pct(score.growth.netIncomeGrowth) },
    ],
  },
];

const CeoInvestPage: React.FC = () => {
  // ── 종목 검색 ──
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [companies, setCompanies] = useState<FinancialCompanyPage | null>(null);
  const [loadingList, setLoadingList] = useState(false);
  const [selected, setSelected] = useState<FinancialCompany | null>(null);

  // ── 투자 점수 ──
  const [score, setScore] = useState<InvestmentScore | null>(null);
  const [loadingScore, setLoadingScore] = useState(false);

  // ── 재원 ──
  const [sellerId, setSellerId] = useState<number>(1);
  const [funding, setFunding] = useState<InvestmentFunding | null>(null);
  const [loadingFunding, setLoadingFunding] = useState(false);

  // ── 주문 ──
  const [amount, setAmount] = useState<number>(1_000_000);
  const [orders, setOrders] = useState<InvestmentOrder[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    financialApi
      .companies(query, page, 10)
      .then((data) => {
        if (!cancelled) setCompanies(data);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err.response?.data?.message || '기업 목록 조회에 실패했습니다.');
      })
      .finally(() => {
        if (!cancelled) setLoadingList(false);
      });
    return () => {
      cancelled = true;
    };
  }, [query, page]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQuery(keyword);
  };

  const openCompany = async (company: FinancialCompany) => {
    setSelected(company);
    setScore(null);
    setLoadingScore(true);
    setError(null);
    try {
      setScore(await investmentApi.score(company.stockCode));
    } catch (err: any) {
      setError(err.response?.data?.message || '투자 점수 조회에 실패했습니다.');
    } finally {
      setLoadingScore(false);
    }
  };

  const loadFunding = async (sid: number) => {
    setLoadingFunding(true);
    setError(null);
    try {
      setFunding(await investmentApi.funding(sid));
    } catch (err: any) {
      setError(err.response?.data?.message || '투자 재원 조회에 실패했습니다.');
    } finally {
      setLoadingFunding(false);
    }
  };

  const loadOrders = async (sid: number) => {
    try {
      setOrders(await investmentApi.ordersBySeller(sid));
    } catch (err: any) {
      setError(err.response?.data?.message || '투자 주문 목록 조회에 실패했습니다.');
    }
  };

  const handleFundingLookup = async () => {
    await Promise.all([loadFunding(sellerId), loadOrders(sellerId)]);
  };

  const handleOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) {
      setError('먼저 종목을 선택하세요.');
      return;
    }
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const created = await investmentApi.createOrder({ sellerId, stockCode: selected.stockCode, amount });
      setNotice(`투자 주문 생성 — #${created.id} (${created.gradeAtOrder}, ${fmtWon(created.amount)})`);
      await Promise.all([loadFunding(sellerId), loadOrders(sellerId)]);
    } catch (err: any) {
      if (err.response?.status === 422) {
        setError(err.response?.data?.message || '투자 부적격 또는 재원 부족으로 주문이 거절되었습니다.');
      } else {
        setError(err.response?.data?.message || '투자 주문 생성에 실패했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleAction = async (id: number, action: 'execute' | 'cancel') => {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      const updated = action === 'execute' ? await investmentApi.execute(id) : await investmentApi.cancel(id);
      setNotice(`#${id} ${action === 'execute' ? '집행' : '취소'} 완료 — ${updated.status}`);
      await Promise.all([loadFunding(sellerId), loadOrders(sellerId)]);
    } catch (err: any) {
      if (err.response?.status === 422) {
        setError(err.response?.data?.message || '처리 조건을 충족하지 못했습니다.');
      } else {
        setError(err.response?.data?.message || '주문 처리에 실패했습니다.');
      }
    } finally {
      setBusyId(null);
    }
  };

  const axes = score ? buildAxes(score) : [];

  return (
    <div className="min-h-screen bg-slate-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto space-y-6">
        <section className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
          <div>
            <h1 className="text-3xl font-bold text-slate-950">투자하기</h1>
            <p className="mt-2 text-sm text-slate-600">
              코스피 상장사를 재무 점수로 평가하고, 확정 정산금 재원으로 투자 주문을 실행합니다.
            </p>
          </div>
          <form onSubmit={handleSearch} className="flex w-full gap-2 md:w-[420px]">
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="기업명 또는 종목코드"
              className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-500"
            />
            <button type="submit" className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-700">
              검색
            </button>
          </form>
        </section>

        {error && <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
        {notice && <div className="rounded-md border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</div>}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[360px_1fr]">
          <Card title="종목 선택">
            {loadingList ? (
              <div className="flex justify-center py-10"><Spinner /></div>
            ) : (
              <div className="space-y-2">
                {companies?.content.map((company) => (
                  <button
                    key={company.stockCode}
                    onClick={() => openCompany(company)}
                    className={`w-full rounded-md border px-4 py-3 text-left transition-colors ${
                      selected?.stockCode === company.stockCode
                        ? 'border-slate-900 bg-slate-100'
                        : 'border-slate-200 hover:border-slate-400 hover:bg-slate-50'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <span className="font-semibold text-slate-900">{company.name}</span>
                      <span className="font-mono text-xs text-slate-500">{company.stockCode}</span>
                    </div>
                    <p className="mt-1 text-xs text-slate-500">{company.market}</p>
                  </button>
                ))}
                {companies && companies.content.length === 0 && (
                  <p className="py-6 text-center text-sm text-slate-400">검색 결과가 없습니다.</p>
                )}
                {companies && companies.totalPages > 1 && (
                  <div className="flex items-center justify-between pt-3 text-sm text-slate-600">
                    <button disabled={page === 0} onClick={() => setPage(page - 1)} className="rounded border px-3 py-1 disabled:opacity-40">
                      이전
                    </button>
                    <span>{page + 1} / {companies.totalPages}</span>
                    <button
                      disabled={page + 1 >= companies.totalPages}
                      onClick={() => setPage(page + 1)}
                      className="rounded border px-3 py-1 disabled:opacity-40"
                    >
                      다음
                    </button>
                  </div>
                )}
              </div>
            )}
          </Card>

          <div className="space-y-6">
            {/* ── 투자 점수 카드 ── */}
            {!selected && (
              <Card>
                <div className="py-14 text-center">
                  <p className="text-lg font-semibold text-slate-800">종목을 선택하면 투자 점수가 산출됩니다.</p>
                  <p className="mt-2 text-sm text-slate-500">수익성 · 안정성 · 성장성 3축을 재무제표로 평가합니다.</p>
                </div>
              </Card>
            )}

            {loadingScore && (
              <Card>
                <div className="flex justify-center py-16"><Spinner /></div>
              </Card>
            )}

            {score && !loadingScore && (
              <Card>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div>
                    <h2 className="text-2xl font-bold text-slate-950">
                      {score.companyName}
                      <span className="ml-2 font-mono text-sm font-medium text-slate-400">({score.stockCode})</span>
                    </h2>
                    <p className="mt-1 text-sm text-slate-500">{score.market} · {score.fiscalYear} 기준</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`rounded-lg px-4 py-2 text-xl font-bold ${gradeClass(score.grade)}`}>{score.grade}</span>
                    <span
                      className={`rounded-full px-3 py-1 text-xs font-semibold ${
                        score.investable ? 'bg-emerald-100 text-emerald-800' : 'bg-red-100 text-red-800'
                      }`}
                    >
                      {score.investable ? '투자 적격' : '투자 부적격'}
                    </span>
                  </div>
                </div>

                {/* 총점 게이지 */}
                <div className="mt-6">
                  <div className="flex items-baseline justify-between">
                    <span className="text-sm font-semibold text-slate-600">종합 점수</span>
                    <span className="text-2xl font-bold text-slate-900">{score.totalScore}<span className="text-sm font-medium text-slate-400">/100</span></span>
                  </div>
                  <div className="mt-2 h-3 w-full overflow-hidden rounded-full bg-slate-100">
                    <div
                      className={`h-full rounded-full ${score.investable ? 'bg-emerald-500' : 'bg-red-500'}`}
                      style={{ width: `${Math.max(0, Math.min(100, score.totalScore))}%` }}
                    />
                  </div>
                </div>

                {/* 축별 점수 바 */}
                <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
                  {axes.map((axis) => {
                    const ratio = axis.axis.maxScore > 0 ? (axis.axis.score / axis.axis.maxScore) * 100 : 0;
                    return (
                      <div key={axis.key} className="rounded-md border border-slate-200 p-4">
                        <div className="flex items-baseline justify-between">
                          <span className="text-sm font-semibold text-slate-700">{axis.label}</span>
                          <span className="text-sm font-bold text-slate-900">{axis.axis.score}<span className="text-xs font-medium text-slate-400">/{axis.axis.maxScore}</span></span>
                        </div>
                        <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-100">
                          <div className="h-full rounded-full bg-slate-700" style={{ width: `${Math.max(0, Math.min(100, ratio))}%` }} />
                        </div>
                        <dl className="mt-3 space-y-1 text-xs text-slate-600">
                          {axis.metrics.map((m) => (
                            <div key={m.label} className="flex justify-between">
                              <dt>{m.label}</dt>
                              <dd className="font-semibold text-slate-800">{m.value}</dd>
                            </div>
                          ))}
                        </dl>
                      </div>
                    );
                  })}
                </div>
              </Card>
            )}

            {/* ── 재원 카드 ── */}
            <Card title="투자 재원">
              <div className="flex flex-wrap items-end gap-3">
                <div className="w-40">
                  <label className="mb-1.5 block text-sm font-medium text-slate-700">셀러 ID</label>
                  <input
                    type="number"
                    min={1}
                    value={sellerId}
                    onChange={(e) => setSellerId(Number(e.target.value))}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-sm focus:border-slate-500 focus:ring-2 focus:ring-slate-500"
                  />
                </div>
                <button
                  onClick={handleFundingLookup}
                  className="rounded-lg bg-slate-800 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-900"
                >
                  조회
                </button>
              </div>

              {loadingFunding ? (
                <div className="py-6"><Spinner size="sm" /></div>
              ) : funding ? (
                <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                    <p className="text-xs font-semibold text-slate-500">확정 정산금</p>
                    <p className="mt-1 text-xl font-bold text-slate-900">{fmtAmount(funding.confirmedTotal)}</p>
                  </div>
                  <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                    <p className="text-xs font-semibold text-slate-500">투자 집행</p>
                    <p className="mt-1 text-xl font-bold text-slate-900">{fmtAmount(funding.investedTotal)}</p>
                  </div>
                  <div className="rounded-md border border-emerald-200 bg-emerald-50 px-4 py-3">
                    <p className="text-xs font-semibold text-emerald-700">투자 가능</p>
                    <p className="mt-1 text-xl font-bold text-emerald-900">{fmtAmount(funding.available)}</p>
                  </div>
                </div>
              ) : (
                <p className="mt-4 text-sm text-slate-400">셀러 ID로 조회하면 재원 현황이 표시됩니다.</p>
              )}
            </Card>

            {/* ── 투자 주문 ── */}
            <Card title="투자 주문">
              <form onSubmit={handleOrder} className="flex flex-wrap items-end gap-3">
                <div className="flex-1 min-w-[180px]">
                  <label className="mb-1.5 block text-sm font-medium text-slate-700">투자 금액(원)</label>
                  <input
                    type="number"
                    min={1}
                    step="any"
                    required
                    value={amount}
                    onChange={(e) => setAmount(Number(e.target.value))}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-sm focus:border-slate-500 focus:ring-2 focus:ring-slate-500"
                  />
                </div>
                <button
                  type="submit"
                  disabled={submitting || !selected}
                  className="rounded-lg bg-slate-900 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-700 disabled:opacity-40"
                >
                  {submitting ? '주문 중...' : selected ? `${selected.name} 투자 주문` : '종목 먼저 선택'}
                </button>
              </form>

              {orders.length === 0 ? (
                <p className="mt-4 text-sm text-slate-400">주문 내역이 없습니다. 재원 조회 후 주문을 생성하세요.</p>
              ) : (
                <div className="mt-4 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-slate-500">
                        <th className="px-2 py-2">#</th>
                        <th className="px-2 py-2">종목</th>
                        <th className="px-2 py-2">금액</th>
                        <th className="px-2 py-2">등급</th>
                        <th className="px-2 py-2">상태</th>
                        <th className="px-2 py-2 text-right">액션</th>
                      </tr>
                    </thead>
                    <tbody>
                      {orders.map((o) => (
                        <tr key={o.id} className="border-b last:border-0">
                          <td className="px-2 py-2.5 font-medium">#{o.id}</td>
                          <td className="px-2 py-2.5 font-mono text-xs text-slate-600">{o.stockCode}</td>
                          <td className="px-2 py-2.5 font-semibold">{fmtWon(o.amount)}</td>
                          <td className="px-2 py-2.5">{o.gradeAtOrder} ({o.scoreAtOrder})</td>
                          <td className="px-2 py-2.5">
                            <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${orderStatusBadge(o.status)}`}>
                              {o.status}
                            </span>
                          </td>
                          <td className="px-2 py-2.5 text-right">
                            {(o.status === 'REQUESTED' || o.status === 'APPROVED') ? (
                              <div className="flex justify-end gap-2">
                                <button
                                  disabled={busyId === o.id}
                                  onClick={() => handleAction(o.id, 'execute')}
                                  className="rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-emerald-700 disabled:opacity-40"
                                >
                                  {busyId === o.id ? '...' : '집행'}
                                </button>
                                <button
                                  disabled={busyId === o.id}
                                  onClick={() => handleAction(o.id, 'cancel')}
                                  className="rounded-lg bg-gray-200 px-3 py-1.5 text-xs font-semibold text-gray-700 hover:bg-gray-300 disabled:opacity-40"
                                >
                                  취소
                                </button>
                              </div>
                            ) : (
                              <span className="text-xs text-slate-400">-</span>
                            )}
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

        <p className="mt-6 text-xs text-slate-400">
          ※ 본 투자점수와 지표는 공시 회계자료를 근거로 산출한 정보 제공 목적의 참고 자료이며, 특정 종목의 매수·매도
          권유나 수익 보장이 아닙니다. 투자 판단과 그 결과에 대한 책임은 이용자 본인에게 있습니다.
        </p>
      </div>
    </div>
  );
};

export default CeoInvestPage;
