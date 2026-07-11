import React, { useEffect, useState } from 'react';
import {
  accountApi,
  type AccountInvestmentAggregate,
  type AccountLoanAggregate,
  type AccountSettlementAggregate,
  type OwnerAccounts,
  type OwnerType,
  type TrialBalance,
} from '@/api/account';
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

const fmtWon = (value: number) => value.toLocaleString('ko-KR') + '원';

const CeoAccountPage: React.FC = () => {
  const [loanAgg, setLoanAgg] = useState<AccountLoanAggregate | null>(null);
  const [investAgg, setInvestAgg] = useState<AccountInvestmentAggregate | null>(null);
  const [settlementAgg, setSettlementAgg] = useState<AccountSettlementAggregate | null>(null);
  const [trialBalance, setTrialBalance] = useState<TrialBalance | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // owner 조회
  const [ownerType, setOwnerType] = useState<OwnerType>('SELLER');
  const [ownerId, setOwnerId] = useState<number>(1);
  const [owner, setOwner] = useState<OwnerAccounts | null>(null);
  const [loadingOwner, setLoadingOwner] = useState(false);
  const [ownerError, setOwnerError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      accountApi.loanAggregate(),
      accountApi.investmentAggregate(),
      accountApi.settlementAggregate(),
      accountApi.trialBalance(),
    ])
      .then(([loan, invest, settlement, tb]) => {
        if (cancelled) return;
        setLoanAgg(loan);
        setInvestAgg(invest);
        setSettlementAgg(settlement);
        setTrialBalance(tb);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err.response?.data?.message || '계정계 집계 조회에 실패했습니다.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const lookupOwner = async () => {
    setLoadingOwner(true);
    setOwnerError(null);
    setOwner(null);
    try {
      setOwner(await accountApi.ownerAccounts(ownerType, ownerId));
    } catch (err: any) {
      setOwnerError(err.response?.data?.message || '계정 잔액 조회에 실패했습니다.');
    } finally {
      setLoadingOwner(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto space-y-6">
        <section>
          <h1 className="text-3xl font-bold text-slate-950">계정계 현황</h1>
          <p className="mt-2 text-sm text-slate-600">
            대출 · 투자 · 정산 자금 흐름을 복식부기 원장 기준으로 집계하고 시산표 균형을 점검합니다.
          </p>
        </section>

        {error && <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

        {loading ? (
          <Card>
            <div className="flex justify-center py-16"><Spinner /></div>
          </Card>
        ) : (
          <>
            {/* ── 집계 카드 3종 ── */}
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              <Card title="대출">
                {loanAgg ? (
                  <dl className="space-y-2.5 text-sm">
                    <div className="flex justify-between"><dt className="text-slate-500">실행 총액</dt><dd className="font-bold text-slate-900">{fmtAmount(loanAgg.disbursedTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">상환 총액</dt><dd className="font-semibold text-slate-800">{fmtAmount(loanAgg.repaidTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">잔액</dt><dd className="font-bold text-slate-900">{fmtAmount(loanAgg.outstanding)}</dd></div>
                    <div className="mt-1 border-t border-slate-100 pt-2.5" />
                    <div className="flex justify-between"><dt className="text-slate-500">기업대출 실행</dt><dd className="font-semibold text-slate-800">{fmtAmount(loanAgg.corporateDisbursedTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">기업대출 잔액</dt><dd className="font-semibold text-slate-800">{fmtAmount(loanAgg.corporateOutstanding)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-400">분개 수</dt><dd className="text-slate-500">{loanAgg.entryCount.toLocaleString('ko-KR')}건</dd></div>
                  </dl>
                ) : <p className="text-sm text-slate-400">집계 없음</p>}
              </Card>

              <Card title="투자">
                {investAgg ? (
                  <dl className="space-y-2.5 text-sm">
                    <div className="flex justify-between"><dt className="text-slate-500">집행 총액</dt><dd className="font-bold text-slate-900">{fmtAmount(investAgg.investedTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">주문 건수</dt><dd className="font-semibold text-slate-800">{investAgg.orderCount.toLocaleString('ko-KR')}건</dd></div>
                  </dl>
                ) : <p className="text-sm text-slate-400">집계 없음</p>}
              </Card>

              <Card title="정산">
                {settlementAgg ? (
                  <dl className="space-y-2.5 text-sm">
                    <div className="flex justify-between"><dt className="text-slate-500">예정 총액</dt><dd className="font-bold text-slate-900">{fmtAmount(settlementAgg.scheduledTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">확정 총액</dt><dd className="font-semibold text-slate-800">{fmtAmount(settlementAgg.confirmedTotal)}</dd></div>
                    <div className="flex justify-between"><dt className="text-slate-500">미확정</dt><dd className="font-semibold text-amber-700">{fmtAmount(settlementAgg.pendingScheduled)}</dd></div>
                  </dl>
                ) : <p className="text-sm text-slate-400">집계 없음</p>}
              </Card>
            </div>

            {/* ── 시산표 ── */}
            <Card>
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-900">시산표</h3>
                {trialBalance && (
                  <span
                    className={`rounded-full px-3 py-1 text-xs font-semibold ${
                      trialBalance.balanced ? 'bg-emerald-100 text-emerald-800' : 'bg-red-100 text-red-800'
                    }`}
                  >
                    {trialBalance.balanced ? '차·대 일치' : '불일치'}
                  </span>
                )}
              </div>
              {trialBalance ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-slate-500">
                        <th className="px-3 py-2">계정</th>
                        <th className="px-3 py-2 text-right">차변 합계</th>
                        <th className="px-3 py-2 text-right">대변 합계</th>
                      </tr>
                    </thead>
                    <tbody>
                      {trialBalance.accounts.map((row) => (
                        <tr key={row.account} className="border-b last:border-0">
                          <td className="px-3 py-2.5 font-medium text-slate-800">{row.account}</td>
                          <td className="px-3 py-2.5 text-right font-mono">{fmtWon(row.debitTotal)}</td>
                          <td className="px-3 py-2.5 text-right font-mono">{fmtWon(row.creditTotal)}</td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr className={`border-t-2 font-bold ${trialBalance.balanced ? 'text-slate-900' : 'text-red-700'}`}>
                        <td className="px-3 py-2.5">합계</td>
                        <td className="px-3 py-2.5 text-right font-mono">{fmtWon(trialBalance.totalDebit)}</td>
                        <td className="px-3 py-2.5 text-right font-mono">{fmtWon(trialBalance.totalCredit)}</td>
                      </tr>
                    </tfoot>
                  </table>
                  {trialBalance.accounts.length === 0 && (
                    <p className="py-6 text-center text-sm text-slate-400">원장 분개가 없습니다.</p>
                  )}
                </div>
              ) : <p className="text-sm text-slate-400">시산표 없음</p>}
            </Card>

            {/* ── owner 조회 ── */}
            <Card title="계정 잔액 조회">
              <div className="flex flex-wrap items-end gap-3">
                <div className="w-40">
                  <label className="mb-1.5 block text-sm font-medium text-slate-700">소유자 유형</label>
                  <select
                    value={ownerType}
                    onChange={(e) => setOwnerType(e.target.value as OwnerType)}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-sm focus:border-slate-500 focus:ring-2 focus:ring-slate-500"
                  >
                    <option value="SELLER">SELLER (셀러)</option>
                    <option value="CORPORATE">CORPORATE (법인)</option>
                  </select>
                </div>
                <div className="w-40">
                  <label className="mb-1.5 block text-sm font-medium text-slate-700">소유자 ID</label>
                  <input
                    type="number"
                    min={1}
                    value={ownerId}
                    onChange={(e) => setOwnerId(Number(e.target.value))}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-sm focus:border-slate-500 focus:ring-2 focus:ring-slate-500"
                  />
                </div>
                <button
                  onClick={lookupOwner}
                  className="rounded-lg bg-slate-800 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-900"
                >
                  조회
                </button>
              </div>

              {ownerError && <div className="mt-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{ownerError}</div>}

              {loadingOwner ? (
                <div className="py-6"><Spinner size="sm" /></div>
              ) : owner ? (
                <div className="mt-4">
                  <p className="mb-3 text-sm text-slate-500">
                    {owner.ownerType} #{owner.ownerId} · 분개 {owner.entryCount.toLocaleString('ko-KR')}건
                  </p>
                  {owner.balances.length === 0 ? (
                    <p className="text-sm text-slate-400">잔액이 없습니다.</p>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b text-left text-slate-500">
                            <th className="px-3 py-2">계정</th>
                            <th className="px-3 py-2">구분</th>
                            <th className="px-3 py-2 text-right">잔액</th>
                          </tr>
                        </thead>
                        <tbody>
                          {owner.balances.map((b) => (
                            <tr key={`${b.account}-${b.side}`} className="border-b last:border-0">
                              <td className="px-3 py-2.5 font-medium text-slate-800">{b.account}</td>
                              <td className="px-3 py-2.5">
                                <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${b.side === 'DEBIT' ? 'bg-blue-100 text-blue-800' : 'bg-purple-100 text-purple-800'}`}>
                                  {b.side === 'DEBIT' ? '차변' : '대변'}
                                </span>
                              </td>
                              <td className="px-3 py-2.5 text-right font-mono font-semibold">{fmtWon(b.balance)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              ) : (
                <p className="mt-4 text-sm text-slate-400">소유자 유형과 ID로 조회하면 계정별 잔액이 표시됩니다.</p>
              )}
            </Card>
          </>
        )}
      </div>
    </div>
  );
};

export default CeoAccountPage;
