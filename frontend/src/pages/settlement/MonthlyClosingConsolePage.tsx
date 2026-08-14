import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  monthlyClosingApi,
  type MonthlyClosing,
  type MonthlyClosingRun,
} from '@/api/monthlyClosing';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 정보계 월마감 콘솔 (ADMIN 전용).
 *
 * <p>대상 월의 DONE 정산을 셀러별로 집계해 마트에 적재한다. 재실행은 기간 전체 교체라 멱등이지만
 * 원장이 마감된 기간은 409 로 거부된다 — 확정 장부 위에 새 집계를 덮지 못하게 하는 안전장치다.
 *
 * <p>이 화면이 특히 신경 쓰는 값은 <b>unmapped·pending</b> 이다. 둘 다 0 이 아니면 집계에서 빠진
 * 정산이 있다는 뜻이라, 합계가 그럴듯해 보여도 마감으로 확정하면 안 된다. 그래서 총액보다
 * 먼저 눈에 들어오게 배치하고 0 이 아닐 때 경고한다.
 */

const STATUS_STYLE: Record<string, string> = {
  RUNNING: 'bg-blue-100 text-blue-800',
  COMPLETED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
};

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

/** 지난달 — 이번 달은 아직 정산이 끝나지 않아 마감 대상이 아니다. */
const lastMonth = () => {
  const d = new Date();
  d.setMonth(d.getMonth() - 1);
  return d.toISOString().slice(0, 7);
};

const MonthlyClosingConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [periodYm, setPeriodYm] = useState(lastMonth());
  const [closing, setClosing] = useState<MonthlyClosing | null>(null);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async (target: string) => {
    setLoading(true);
    setNotice(null);
    try {
      setClosing(await monthlyClosingApi.get(target));
    } catch (err) {
      setClosing(null);
      // 404 는 오류가 아니라 "아직 마감을 안 돌렸다" 이다 — 에러로 물들이면 운영자가 겁먹는다.
      setNotice(apiErrorStatus(err) === 404
        ? `${target} 마감 이력이 없습니다. 아래에서 실행할 수 있습니다.`
        : apiErrorMessage(err, '마감 정보를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(periodYm); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleRun = useCallback(async () => {
    const already = closing?.run.status === 'COMPLETED';
    const message = already
      ? `${periodYm} 마감을 다시 실행합니다.\n`
        + '기존 마트가 이 기간 단위로 통째 교체됩니다. 진행할까요?'
      : `${periodYm} 마감을 실행합니다.\n대상 월의 확정(DONE) 정산을 셀러별로 집계합니다. 진행할까요?`;
    if (!window.confirm(message)) return;

    setRunning(true);
    try {
      const run: MonthlyClosingRun = await monthlyClosingApi.run(periodYm);
      showToast(
        run.status === 'FAILED'
          ? `마감 실패: ${run.failureReason ?? '사유 미상'}`
          : `${periodYm} 마감 완료 — 셀러 ${run.sellerCount}명 · 정산 ${run.settlementCount}건`,
        run.status === 'FAILED' ? 'error' : 'success',
      );
      await load(periodYm);
    } catch (err) {
      // 409 = 원장이 마감된 기간 — 재실행이 막힌 것이지 장애가 아니다.
      showToast(apiErrorStatus(err) === 409
        ? '원장이 마감된 기간이라 재실행할 수 없습니다.'
        : apiErrorMessage(err, '마감 실행에 실패했습니다.'), 'error');
    } finally {
      setRunning(false);
    }
  }, [periodYm, closing, load, showToast]);

  const run = closing?.run ?? null;
  const incomplete = (run?.unmappedCount ?? 0) > 0 || (run?.pendingCount ?? 0) > 0;

  /** 셀러 마트 합계 — 서버 총액과 어긋나면 마트가 부분 적재된 것이다. */
  const sellerNetSum = useMemo(
    () => (closing?.sellers ?? []).reduce((acc, s) => acc + Number(s.netAmount), 0),
    [closing],
  );
  const totalsMismatch = run?.totalNet != null && Number(run.totalNet) !== sellerNetSum;

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">월마감</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          대상 월의 확정 정산을 셀러별로 집계해 마트에 적재합니다. 재실행은 기간 단위 교체이며,
          원장이 마감된 기간은 실행할 수 없습니다.
        </p>
      </div>

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="text-[11px] text-gray-500">대상 월</span>
            <input type="month" value={periodYm} onChange={(e) => setPeriodYm(e.target.value)} className="input" />
          </label>
          <button
            onClick={() => void load(periodYm)}
            disabled={loading}
            className="px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            조회
          </button>
          <button
            onClick={() => void handleRun()}
            disabled={running || loading}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
          >
            {running ? '마감 중…' : run ? '마감 재실행' : '마감 실행'}
          </button>
          {run && (
            <span className={`ml-auto text-xs px-2 py-1 rounded-full font-semibold ${STATUS_STYLE[run.status] ?? 'bg-gray-100'}`}>
              {run.status}
            </span>
          )}
        </div>
        {notice && <p className="text-sm text-gray-600 mt-2">{notice}</p>}
        {run?.failureReason && (
          <p className="text-sm text-red-700 bg-red-50 border border-red-100 rounded px-3 py-2 mt-2">
            실패 사유: {run.failureReason}
          </p>
        )}
      </section>

      {loading && <div className="flex justify-center py-6"><Spinner /></div>}

      {run && (
        <>
          {/* 완전성 신호를 총액보다 먼저 — 빠진 정산이 있으면 합계는 그럴듯해도 확정하면 안 된다 */}
          <section className={`rounded-xl border p-4 mb-4 ${
            incomplete ? 'bg-amber-50 border-amber-200' : 'bg-white border-gray-200'
          }`}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div>
                <p className="text-[11px] text-gray-500">셀러 매핑 누락</p>
                <p className={`text-lg font-bold ${run.unmappedCount > 0 ? 'text-amber-800' : 'text-gray-900'}`}>
                  {run.unmappedCount}건
                </p>
              </div>
              <div>
                <p className="text-[11px] text-gray-500">미확정 정산</p>
                <p className={`text-lg font-bold ${run.pendingCount > 0 ? 'text-amber-800' : 'text-gray-900'}`}>
                  {run.pendingCount}건
                </p>
              </div>
              <div>
                <p className="text-[11px] text-gray-500">집계 셀러</p>
                <p className="text-lg font-bold text-gray-900">{run.sellerCount}명</p>
              </div>
              <div>
                <p className="text-[11px] text-gray-500">집계 정산</p>
                <p className="text-lg font-bold text-gray-900">{run.settlementCount}건</p>
              </div>
            </div>
            {incomplete && (
              <p className="text-xs text-amber-900 mt-3">
                집계에서 빠진 정산이 있습니다. 매핑 누락은 셀러 연결을, 미확정은 정산 확정을 먼저 처리한 뒤
                재실행하세요 — 이 상태로 확정하면 마트가 실제보다 작게 남습니다.
              </p>
            )}
            <p className="text-[11px] text-gray-500 mt-3">
              실행 {dateTime(run.startedAt)} ~ {dateTime(run.finishedAt)} · {run.triggeredBy ?? '-'}
            </p>
          </section>

          <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
            <dl className="grid grid-cols-2 md:grid-cols-5 gap-4">
              {[
                ['총 매출(gross)', run.totalGross],
                ['환불', run.totalRefunded],
                ['수수료', run.totalCommission],
                ['홀드백', run.totalHoldback],
                ['정산 net', run.totalNet],
              ].map(([label, value]) => (
                <div key={label as string}>
                  <dt className="text-[11px] text-gray-500">{label}</dt>
                  <dd className="text-sm font-semibold text-gray-900">{money(value as string | null)}</dd>
                </div>
              ))}
            </dl>
            {totalsMismatch && (
              <p className="text-xs text-red-700 bg-red-50 border border-red-100 rounded px-3 py-2 mt-3">
                run 총액과 셀러 마트 합계가 다릅니다 ({money(run.totalNet)} ≠ {money(String(sellerNetSum))}) —
                마트가 부분 적재됐을 수 있습니다.
              </p>
            )}
          </section>

          <section className="bg-white rounded-xl border border-gray-200 p-4">
            <h3 className="font-bold text-gray-900 text-sm mb-3">셀러 마트 {closing?.sellers.length ?? 0}행</h3>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['셀러', '정산건수', '매출', '환불', '수수료', '홀드백', 'net'].map((h) => (
                      <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {(closing?.sellers ?? []).map((s) => (
                    <tr key={s.sellerId} className="hover:bg-gray-50">
                      <td className="px-3 py-2 font-mono text-xs">{s.sellerId}</td>
                      <td className="px-3 py-2 text-right text-xs">{s.settlementCount}</td>
                      <td className="px-3 py-2 text-right whitespace-nowrap">{money(s.grossAmount)}</td>
                      <td className="px-3 py-2 text-right whitespace-nowrap text-gray-600">{money(s.refundedAmount)}</td>
                      <td className="px-3 py-2 text-right whitespace-nowrap text-gray-600">{money(s.commissionAmount)}</td>
                      <td className="px-3 py-2 text-right whitespace-nowrap text-gray-600">{money(s.holdbackAmount)}</td>
                      <td className="px-3 py-2 text-right whitespace-nowrap font-semibold">{money(s.netAmount)}</td>
                    </tr>
                  ))}
                  {(closing?.sellers.length ?? 0) === 0 && (
                    <tr><td colSpan={7} className="px-3 py-8 text-center text-gray-400">셀러 마트 행이 없습니다.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  );
};

export default MonthlyClosingConsolePage;
