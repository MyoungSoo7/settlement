import React, { useCallback, useMemo, useState } from 'react';
import { recoveryApi, type SellerRecoverySummary } from '@/api/recovery';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 지급후 회수 채권 조회 콘솔.
 *
 * <p>지급이 나간 뒤 환불·차지백·PG대사로 마이너스 조정이 생기면 그 돈은 다음 정산에서 상계해
 * 걷는다. 이 화면은 셀러 한 명의 <b>미상계 잔액</b>과 근거(채권·상계 이력)를 보여 준다.
 *
 * <p><b>버튼이 없는 화면이다</b> — 상계는 정산 파이프라인이 자동 집행하고 서버에 쓰기 API 가
 * 없다. 운영자가 여기서 할 일은 "이 셀러에게 얼마가 남았고 어디서 걷혔는지" 확인하는 것이다.
 */

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

const RecoveryConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [sellerId, setSellerId] = useState('');
  const [summary, setSummary] = useState<SellerRecoverySummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const id = Number(sellerId);
    if (!Number.isFinite(id) || id <= 0) {
      showToast('조회할 셀러 ID 를 입력하세요.', 'warning');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setSummary(await recoveryApi.bySeller(id));
    } catch (err) {
      setSummary(null);
      setError(apiErrorMessage(err, '회수 채권을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [sellerId, showToast]);

  /** 채권별 상계 이력 — 근거를 따라가려면 채권 축으로 묶어 보는 편이 읽힌다. */
  const allocationsByRecovery = useMemo(() => {
    const map = new Map<number, SellerRecoverySummary['allocations']>();
    (summary?.allocations ?? []).forEach((a) => {
      const list = map.get(a.recoveryId) ?? [];
      list.push(a);
      map.set(a.recoveryId, list);
    });
    return map;
  }, [summary]);

  const openCount = summary?.recoveries.filter((r) => r.status === 'OPEN').length ?? 0;

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">회수 채권</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          지급 후 생긴 마이너스 조정을 다음 정산에서 상계해 걷습니다. 상계는 정산 파이프라인이 자동 집행하며,
          이 화면은 잔액과 근거를 확인하는 읽기 전용입니다.
        </p>
      </div>

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="text-[11px] text-gray-500">셀러 ID</span>
            <input
              value={sellerId}
              onChange={(e) => setSellerId(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void load(); }}
              placeholder="예: 42"
              inputMode="numeric"
              className="input w-40 font-mono"
            />
          </label>
          <button
            onClick={() => void load()}
            disabled={loading}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
          >
            {loading ? '조회 중…' : '조회'}
          </button>
          {summary && (
            <div className="ml-auto text-right">
              <p className="text-[11px] text-gray-500">미상계 잔액 (OPEN {openCount}건)</p>
              <p className={`text-lg font-bold ${Number(summary.outstandingTotal) > 0 ? 'text-red-700' : 'text-green-700'}`}>
                {money(summary.outstandingTotal)}
              </p>
            </div>
          )}
        </div>
        {error && <p className="text-sm text-orange-700 mt-2">{error}</p>}
      </section>

      {loading && <div className="flex justify-center py-6"><Spinner /></div>}

      {summary && (
        <section className="bg-white rounded-xl border border-gray-200 p-4">
          <h3 className="font-bold text-gray-900 text-sm mb-3">
            채권 {summary.recoveries.length}건 · 상계 이력 {summary.allocations.length}건
          </h3>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['ID', '원조정', '원금', '상계됨', '잔액', '상태', '생성', '종료'].map((h) => (
                    <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {summary.recoveries.map((r) => {
                  const allocations = allocationsByRecovery.get(r.id) ?? [];
                  return (
                    <React.Fragment key={r.id}>
                      <tr className={r.status === 'OPEN' ? 'bg-red-50/40' : ''}>
                        <td className="px-3 py-2 font-mono text-xs">{r.id}</td>
                        <td className="px-3 py-2 font-mono text-xs text-gray-500">{r.sourceAdjustmentId ?? '-'}</td>
                        <td className="px-3 py-2 text-right whitespace-nowrap">{money(r.originalAmount)}</td>
                        <td className="px-3 py-2 text-right whitespace-nowrap text-gray-600">{money(r.allocatedAmount)}</td>
                        <td className={`px-3 py-2 text-right whitespace-nowrap font-semibold ${
                          r.status === 'OPEN' ? 'text-red-700' : 'text-gray-400'
                        }`}>
                          {money(r.outstanding)}
                        </td>
                        <td className="px-3 py-2">
                          <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${
                            r.status === 'OPEN' ? 'bg-yellow-100 text-yellow-800' : 'bg-gray-200 text-gray-600'
                          }`}>
                            {r.status}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-xs text-gray-500">{dateTime(r.createdAt)}</td>
                        <td className="px-3 py-2 text-xs text-gray-500">{dateTime(r.closedAt)}</td>
                      </tr>
                      {allocations.length > 0 && (
                        <tr>
                          <td colSpan={8} className="px-3 pb-3 pt-0">
                            <div className="ml-6 border-l-2 border-gray-100 pl-3">
                              <p className="text-[11px] text-gray-500 mb-1">상계 이력</p>
                              {allocations.map((a) => (
                                <p key={a.id} className="text-xs font-mono text-gray-600">
                                  정산 #{a.settlementId ?? '-'} 에서 {money(a.amount)} · {dateTime(a.createdAt)}
                                </p>
                              ))}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
                {summary.recoveries.length === 0 && (
                  <tr>
                    <td colSpan={8} className="px-3 py-8 text-center text-gray-400">
                      이 셀러에게는 회수 채권이 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {/* 채권에 매달리지 않은 상계는 데이터 이상이다 — 조용히 감추지 않고 드러낸다 */}
          {summary.allocations.some((a) => !summary.recoveries.some((r) => r.id === a.recoveryId)) && (
            <p className="mt-3 text-xs text-orange-700 bg-orange-50 border border-orange-100 rounded px-3 py-2">
              대응하는 채권이 목록에 없는 상계 이력이 있습니다 — 데이터 정합을 확인하세요.
            </p>
          )}
        </section>
      )}
    </div>
  );
};

export default RecoveryConsolePage;
