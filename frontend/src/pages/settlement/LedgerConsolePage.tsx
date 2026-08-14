import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ledgerApi, type LedgerEntry, type LedgerPeriod, type TrialBalance } from '@/api/ledger';
import { authApi } from '@/api/auth';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 원장·시산표 콘솔.
 *
 * <p>두 표면의 권한 등급이 다르다: 분개 조회(`/api/ledger/**`)는 ADMIN·MANAGER, 기간·시산표·마감
 * (`/admin/ledger-periods/**`)은 ADMIN 전용이다. MANAGER 에게 시산표 패널을 보여 주면 누를 때마다
 * 403 이 나는 죽은 UI 가 되므로 역할로 갈라서 그린다(서버 게이트를 화면이 그대로 반영).
 *
 * <p>원장 규칙상 POSTED 분개는 수정할 수 없고 정정은 역분개로만 한다. 그래서 이 화면에는
 * 분개를 고치는 수단이 없다 — 유일한 쓰기 동작은 기간 마감이고, 그것도 되돌릴 수 없다.
 */

const STATUS_STYLE: Record<string, string> = {
  POSTED: 'bg-green-100 text-green-800',
  PENDING: 'bg-yellow-100 text-yellow-800',
  REVERSED: 'bg-gray-200 text-gray-600',
};

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

const thisMonth = () => new Date().toISOString().slice(0, 7);
const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);

type DrillKind = 'settlement' | 'refund';

const LedgerConsolePage: React.FC = () => {
  const { showToast } = useToast();
  const isAdmin = authApi.getCurrentUser()?.role === 'ADMIN';

  // ── 시산표·기간 (ADMIN)
  const [periodYm, setPeriodYm] = useState(thisMonth());
  const [trialBalance, setTrialBalance] = useState<TrialBalance | null>(null);
  const [period, setPeriod] = useState<LedgerPeriod | null>(null);
  const [periodLoading, setPeriodLoading] = useState(false);
  const [periodError, setPeriodError] = useState<string | null>(null);
  const [closing, setClosing] = useState(false);

  // ── 분개 조회 (ADMIN·MANAGER)
  const [from, setFrom] = useState(daysAgo(30));
  const [to, setTo] = useState(daysAgo(0));
  const [entries, setEntries] = useState<LedgerEntry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [entriesError, setEntriesError] = useState<string | null>(null);
  const [drillKind, setDrillKind] = useState<DrillKind>('settlement');
  const [drillId, setDrillId] = useState('');
  const [scope, setScope] = useState<string>('기간');

  const loadPeriod = useCallback(async () => {
    if (!isAdmin) return;
    setPeriodLoading(true);
    setPeriodError(null);
    try {
      const [tb, p] = await Promise.all([
        ledgerApi.trialBalance(periodYm),
        ledgerApi.period(periodYm),
      ]);
      setTrialBalance(tb);
      setPeriod(p);
    } catch (err) {
      setTrialBalance(null);
      setPeriod(null);
      setPeriodError(apiErrorMessage(err, '시산표를 불러오지 못했습니다.'));
    } finally {
      setPeriodLoading(false);
    }
  }, [isAdmin, periodYm]);

  const loadEntries = useCallback(async () => {
    setEntriesLoading(true);
    setEntriesError(null);
    try {
      setEntries(await ledgerApi.entries(from, to));
      setScope(`기간 ${from} ~ ${to}`);
    } catch (err) {
      setEntries([]);
      setEntriesError(apiErrorMessage(err, '분개를 불러오지 못했습니다.'));
    } finally {
      setEntriesLoading(false);
    }
  }, [from, to]);

  const loadDrill = useCallback(async () => {
    const id = Number(drillId);
    if (!Number.isFinite(id) || id <= 0) {
      showToast('조회할 ID 를 입력하세요.', 'warning');
      return;
    }
    setEntriesLoading(true);
    setEntriesError(null);
    try {
      const rows = drillKind === 'settlement'
        ? await ledgerApi.bySettlement(id)
        : await ledgerApi.byRefund(id);
      setEntries(rows);
      setScope(`${drillKind === 'settlement' ? '정산' : '환불'} #${id}`);
    } catch (err) {
      setEntries([]);
      setEntriesError(apiErrorMessage(err, '분개를 불러오지 못했습니다.'));
    } finally {
      setEntriesLoading(false);
    }
  }, [drillId, drillKind, showToast]);

  const handleClose = useCallback(async () => {
    if (!trialBalance) return;
    if (!window.confirm(
      `${periodYm} 기간을 마감합니다. 마감 후에는 이 기간에 분개를 붙일 수 없고 되돌릴 수 없습니다. 진행할까요?`
    )) return;

    setClosing(true);
    try {
      const closed = await ledgerApi.close(periodYm);
      setPeriod(closed);
      showToast(`${periodYm} 기간을 마감했습니다.`, 'success');
    } catch (err) {
      showToast(apiErrorMessage(err, '기간 마감에 실패했습니다.'), 'error');
    } finally {
      setClosing(false);
    }
  }, [periodYm, trialBalance, showToast]);

  useEffect(() => { void loadEntries(); void loadPeriod(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const totals = useMemo(() => {
    const sum = entries.reduce((acc, e) => acc + Number(e.amount), 0);
    return { count: entries.length, sum: String(sum) };
  }, [entries]);

  const alreadyClosed = period?.status === 'CLOSED';
  const unbalanced = trialBalance !== null && !trialBalance.balanced;

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">원장 · 시산표</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          복식부기 분개 조회와 월별 시산표. POSTED 분개는 수정할 수 없고 정정은 역분개로만 합니다.
        </p>
      </div>

      {/* ── 시산표 · 기간 마감 (ADMIN 전용) ───────────────────────────── */}
      {isAdmin ? (
        <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
          <div className="flex flex-wrap items-end justify-between gap-3 mb-4">
            <div className="flex items-end gap-3">
              <label className="block">
                <span className="text-[11px] text-gray-500">기간 (YYYY-MM)</span>
                <input type="month" value={periodYm} onChange={(e) => setPeriodYm(e.target.value)} className="input" />
              </label>
              <button
                onClick={() => void loadPeriod()}
                disabled={periodLoading}
                className="px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40"
              >
                조회
              </button>
            </div>

            <div className="flex items-center gap-3">
              {period && (
                <span className="text-xs text-gray-500">
                  상태 <b className={alreadyClosed ? 'text-gray-700' : 'text-green-700'}>{period.status}</b>
                  {period.closedAt && ` · ${dateTime(period.closedAt)} ${period.closedBy ?? ''}`}
                </span>
              )}
              <button
                onClick={() => void handleClose()}
                disabled={closing || alreadyClosed || unbalanced || trialBalance === null}
                title={
                  unbalanced ? '차대가 맞지 않는 기간은 마감할 수 없습니다'
                    : alreadyClosed ? '이미 마감된 기간입니다' : undefined
                }
                className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
              >
                {closing ? '마감 중…' : '기간 마감'}
              </button>
            </div>
          </div>

          {periodError && <p className="text-sm text-orange-700">{periodError}</p>}
          {periodLoading && <div className="flex justify-center py-4"><Spinner /></div>}

          {trialBalance && (
            <>
              <div className={`rounded-lg px-3 py-2 mb-3 text-sm font-semibold ${
                trialBalance.balanced
                  ? 'bg-green-50 text-green-800 border border-green-100'
                  : 'bg-red-50 text-red-800 border border-red-200'
              }`}>
                {trialBalance.balanced
                  ? `차대 일치 — 차변 ${money(trialBalance.totalDebit)} = 대변 ${money(trialBalance.totalCredit)}`
                  : `차대 불균형 — 차변 ${money(trialBalance.totalDebit)} ≠ 대변 ${money(trialBalance.totalCredit)} · 원장 불변식 위반이므로 마감 전에 조사가 필요합니다`}
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      <th className="px-3 py-2 text-left text-xs font-semibold text-gray-500">계정</th>
                      <th className="px-3 py-2 text-right text-xs font-semibold text-gray-500">차변</th>
                      <th className="px-3 py-2 text-right text-xs font-semibold text-gray-500">대변</th>
                      <th className="px-3 py-2 text-right text-xs font-semibold text-gray-500">잔액</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {trialBalance.lines.map((line) => (
                      <tr key={line.account}>
                        <td className="px-3 py-2 font-mono text-xs">{line.account}</td>
                        <td className="px-3 py-2 text-right">{money(line.debit)}</td>
                        <td className="px-3 py-2 text-right">{money(line.credit)}</td>
                        <td className="px-3 py-2 text-right font-semibold">{money(line.net)}</td>
                      </tr>
                    ))}
                    {trialBalance.lines.length === 0 && (
                      <tr><td colSpan={4} className="px-3 py-6 text-center text-gray-400">이 기간에 분개가 없습니다.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </section>
      ) : (
        <p className="bg-gray-50 border border-gray-200 rounded-lg px-3 py-2 text-xs text-gray-500 mb-6">
          시산표·기간 마감은 최고 관리자 전용입니다. 아래 분개 조회만 이용할 수 있습니다.
        </p>
      )}

      {/* ── 분개 조회 (ADMIN·MANAGER) ────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4">
        <div className="flex flex-wrap items-end gap-3 mb-4">
          <label className="block">
            <span className="text-[11px] text-gray-500">시작</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="input" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">종료</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="input" />
          </label>
          <button
            onClick={() => void loadEntries()}
            disabled={entriesLoading}
            className="px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            기간 조회
          </button>

          <span className="text-gray-300">|</span>

          <label className="block">
            <span className="text-[11px] text-gray-500">단건 추적</span>
            <select value={drillKind} onChange={(e) => setDrillKind(e.target.value as DrillKind)} className="input">
              <option value="settlement">정산 ID</option>
              <option value="refund">환불 ID</option>
            </select>
          </label>
          <input
            value={drillId}
            onChange={(e) => setDrillId(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void loadDrill(); }}
            placeholder="예: 1024"
            className="input w-32 font-mono"
          />
          <button
            onClick={() => void loadDrill()}
            disabled={entriesLoading}
            className="px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            추적
          </button>
        </div>

        <div className="flex items-center justify-between mb-2">
          <p className="text-xs text-gray-500">{scope}</p>
          <p className="text-xs text-gray-500">
            {totals.count}건 · 합계 <b className="text-gray-800">{money(totals.sum)}</b>
          </p>
        </div>

        {entriesError && <p className="text-sm text-orange-700 mb-2">{entriesError}</p>}
        {entriesLoading && <div className="flex justify-center py-6"><Spinner /></div>}

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['ID', '기준', '유형', '차변', '대변', '금액', '상태', '정산일', '전기일시'].map((h) => (
                  <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {entries.map((e) => (
                <tr key={e.id} className="hover:bg-gray-50">
                  <td className="px-3 py-2 font-mono text-xs">{e.id}</td>
                  <td className="px-3 py-2 font-mono text-xs text-gray-500">{e.referenceType} #{e.referenceId}</td>
                  <td className="px-3 py-2 text-xs">{e.entryType}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.debitAccount}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.creditAccount}</td>
                  <td className="px-3 py-2 text-right whitespace-nowrap">{money(e.amount)}</td>
                  <td className="px-3 py-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${STATUS_STYLE[e.status] ?? 'bg-gray-100 text-gray-600'}`}>
                      {e.status}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-xs text-gray-500">{e.settlementDate}</td>
                  <td className="px-3 py-2 text-xs text-gray-500">{dateTime(e.postedAt)}</td>
                </tr>
              ))}
              {!entriesLoading && entries.length === 0 && (
                <tr><td colSpan={9} className="px-3 py-8 text-center text-gray-400">분개가 없습니다.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
};

export default LedgerConsolePage;
