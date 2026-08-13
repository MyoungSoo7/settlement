import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  pgReconciliationApi,
  DISCREPANCY_TYPE_LABEL,
  type ClawbackImpact,
  type Discrepancy,
  type ReconciliationRun,
  type RunDetail,
} from '@/api/pgReconciliation';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * PG 정산파일 대사 콘솔.
 *
 * <p>이 화면의 승인 버튼은 <b>돈을 움직인다</b> — 차이를 승인하면 역정산(SettlementAdjustment)이
 * 뒤따라 셀러에게서 회수가 일어난다. 그래서 승인 전에 회수 영향 미리보기를 강제로 보여 주고,
 * 마감은 미결 0 건일 때만 열어 둔다(CLOSED 는 재개방 경로가 없는 종착 상태다).
 */

const RUN_STATUS_STYLE: Record<string, string> = {
  RUNNING: 'bg-blue-100 text-blue-800',
  COMPLETED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  CLOSED: 'bg-gray-200 text-gray-600',
};

const DISCREPANCY_STATUS_STYLE: Record<string, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800',
  APPROVED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-gray-200 text-gray-600',
  AUTO_CORRECTED: 'bg-blue-100 text-blue-700',
};

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);

const PgReconciliationConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [runs, setRuns] = useState<ReconciliationRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [impact, setImpact] = useState<ClawbackImpact | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  // 업로드 폼
  const [provider, setProvider] = useState('TOSS');
  const [targetDate, setTargetDate] = useState(daysAgo(1));
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);

  const loadRuns = useCallback(async () => {
    setRunsLoading(true);
    setError(null);
    try {
      setRuns(await pgReconciliationApi.runs());
    } catch (err) {
      setError(apiErrorMessage(err, '대사 목록을 불러오지 못했습니다.'));
    } finally {
      setRunsLoading(false);
    }
  }, []);

  /** 상세와 회수 미리보기는 항상 함께 연다 — 승인 판단에 둘 다 필요하다. */
  const openRun = useCallback(async (runId: number) => {
    setDetailLoading(true);
    setImpact(null);
    try {
      const [d, i] = await Promise.all([
        pgReconciliationApi.runDetail(runId),
        pgReconciliationApi.clawbackPreview(runId).catch(() => null),
      ]);
      setDetail(d);
      setImpact(i);
    } catch (err) {
      showToast(apiErrorMessage(err, '대사 상세를 불러오지 못했습니다.'), 'error');
    } finally {
      setDetailLoading(false);
    }
  }, [showToast]);

  useEffect(() => { void loadRuns(); }, [loadRuns]);

  const handleUpload = useCallback(async () => {
    if (!file) {
      showToast('업로드할 CSV 파일을 선택하세요.', 'warning');
      return;
    }
    setUploading(true);
    try {
      const run = await pgReconciliationApi.upload(provider, targetDate, file);
      showToast(`대사 실행 #${run.id} — 차이 ${run.discrepancyCount}건, 자동보정 ${run.autoCorrectedCount}건`,
        run.discrepancyCount > 0 ? 'warning' : 'success');
      await loadRuns();
      await openRun(run.id);
    } catch (err) {
      showToast(apiErrorMessage(err, '업로드에 실패했습니다.'), 'error');
    } finally {
      setUploading(false);
    }
  }, [file, provider, targetDate, loadRuns, openRun, showToast]);

  const resolve = useCallback(async (d: Discrepancy, action: 'approve' | 'reject') => {
    const isApprove = action === 'approve';
    const line = impact?.lines.find((l) => l.discrepancyId === d.id);
    const clawback = line ? money(line.clawbackAmount) : null;

    const message = isApprove
      ? `차이 #${d.id}(${DISCREPANCY_TYPE_LABEL[d.type] ?? d.type})를 승인합니다.\n`
        + (clawback ? `승인하면 셀러에게서 ${clawback} 이 회수됩니다.\n` : '이 유형은 회수가 발생하지 않습니다.\n')
        + '진행할까요?'
      : `차이 #${d.id} 를 거절(무시)합니다. 사유가 기록됩니다. 진행할까요?`;

    if (!window.confirm(message)) return;

    const note = window.prompt(isApprove ? '승인 사유(선택)' : '거절 사유(필수)', '') ?? '';
    if (!isApprove && note.trim() === '') {
      showToast('거절에는 사유가 필요합니다.', 'warning');
      return;
    }

    setBusyId(d.id);
    try {
      if (isApprove) await pgReconciliationApi.approve(d.id, note);
      else await pgReconciliationApi.reject(d.id, note);
      showToast(`차이 #${d.id} 를 ${isApprove ? '승인' : '거절'}했습니다.`, 'success');
      await openRun(d.runId);
      await loadRuns();
    } catch (err) {
      showToast(apiErrorMessage(err, '처리에 실패했습니다.'), 'error');
    } finally {
      setBusyId(null);
    }
  }, [impact, openRun, loadRuns, showToast]);

  const pendingCount = useMemo(
    () => detail?.discrepancies.filter((d) => d.status === 'PENDING').length ?? 0,
    [detail],
  );

  const handleClose = useCallback(async () => {
    if (!detail) return;
    if (!window.confirm(
      `${detail.run.pgProvider} ${detail.run.targetDate} 대사를 마감합니다.\n`
      + '마감하면 같은 (PG, 날짜)로 새 대사를 열 수 없고 되돌릴 수 없습니다. 진행할까요?'
    )) return;

    setBusyId(detail.run.id);
    try {
      await pgReconciliationApi.close(detail.run.id);
      showToast(`대사 #${detail.run.id} 를 마감했습니다.`, 'success');
      await openRun(detail.run.id);
      await loadRuns();
    } catch (err) {
      showToast(apiErrorMessage(err, '마감에 실패했습니다.'), 'error');
    } finally {
      setBusyId(null);
    }
  }, [detail, openRun, loadRuns, showToast]);

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">PG 대사</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          PG 정산파일과 내부 결제 원장을 대조합니다. 차이를 승인하면 역정산이 뒤따라 셀러에게서 회수가 일어납니다.
        </p>
      </div>

      {/* ── 파일 업로드 ─────────────────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="text-[11px] text-gray-500">PG 사</span>
            <input value={provider} onChange={(e) => setProvider(e.target.value)}
              className="input w-32" placeholder="TOSS" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">대상일</span>
            <input type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} className="input" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">정산 CSV</span>
            <input
              type="file" accept=".csv,text/csv"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              className="input"
              aria-label="정산 CSV"
            />
          </label>
          <button
            onClick={() => void handleUpload()}
            disabled={uploading}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
          >
            {uploading ? '대사 중…' : '업로드 + 대사'}
          </button>
        </div>
        <p className="text-[11px] text-gray-400 mt-2">
          헤더: pg_transaction_id, amount, refunded_amount, fee, settled_date · 반올림 차이는 자동 보정되고 나머지는 미결로 쌓입니다.
        </p>
      </section>

      {/* ── 실행 목록 ──────────────────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-bold text-gray-900 text-sm">최근 대사 실행</h3>
          <button onClick={() => void loadRuns()} disabled={runsLoading}
            className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40">
            새로고침
          </button>
        </div>

        {error && <p className="text-sm text-orange-700 mb-2">{error}</p>}
        {runsLoading && <div className="flex justify-center py-4"><Spinner /></div>}

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['ID', 'PG', '대상일', '상태', 'PG행', '내부행', '일치', '차이', '자동보정', '실행시각', ''].map((h) => (
                  <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {runs.map((r) => (
                <tr key={r.id} className={`hover:bg-gray-50 ${detail?.run.id === r.id ? 'bg-blue-50' : ''}`}>
                  <td className="px-3 py-2 font-mono text-xs">{r.id}</td>
                  <td className="px-3 py-2 text-xs">{r.pgProvider}</td>
                  <td className="px-3 py-2 text-xs">{r.targetDate}</td>
                  <td className="px-3 py-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${RUN_STATUS_STYLE[r.status] ?? 'bg-gray-100'}`}>
                      {r.status}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right text-xs">{r.totalPgRows}</td>
                  <td className="px-3 py-2 text-right text-xs">{r.totalInternalRows}</td>
                  <td className="px-3 py-2 text-right text-xs">{r.matchedCount}</td>
                  <td className={`px-3 py-2 text-right text-xs font-semibold ${r.discrepancyCount > 0 ? 'text-red-700' : ''}`}>
                    {r.discrepancyCount}
                  </td>
                  <td className="px-3 py-2 text-right text-xs text-gray-500">{r.autoCorrectedCount}</td>
                  <td className="px-3 py-2 text-xs text-gray-500">{dateTime(r.startedAt)}</td>
                  <td className="px-3 py-2">
                    <button onClick={() => void openRun(r.id)}
                      className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50">
                      열기
                    </button>
                  </td>
                </tr>
              ))}
              {!runsLoading && runs.length === 0 && (
                <tr><td colSpan={11} className="px-3 py-8 text-center text-gray-400">대사 실행 이력이 없습니다.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {/* ── 상세 + 차이 처리 ───────────────────────────────────────── */}
      {detailLoading && <div className="flex justify-center py-6"><Spinner /></div>}

      {detail && (
        <section className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3 mb-4">
            <div>
              <h3 className="font-bold text-gray-900">
                대사 #{detail.run.id} · {detail.run.pgProvider} · {detail.run.targetDate}
              </h3>
              <p className="text-xs text-gray-500 mt-0.5">
                {detail.run.fileName ?? '파일명 없음'} · 미결 {pendingCount}건
                {detail.run.closed && ` · ${dateTime(detail.run.closedAt)} ${detail.run.closedBy ?? ''} 마감`}
              </p>
            </div>
            <button
              onClick={() => void handleClose()}
              disabled={busyId !== null || detail.run.closed || pendingCount > 0}
              title={
                detail.run.closed ? '이미 마감된 대사입니다'
                  : pendingCount > 0 ? `미결 ${pendingCount}건을 먼저 처리해야 마감할 수 있습니다` : undefined
              }
              className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
            >
              대사 마감
            </button>
          </div>

          {/* 회수 영향 — 승인 전에 반드시 보이는 자리에 둔다 */}
          {impact && (
            <div className={`rounded-lg px-3 py-2 mb-4 text-sm border ${
              impact.clawbackCount > 0
                ? 'bg-amber-50 border-amber-200 text-amber-900'
                : 'bg-gray-50 border-gray-200 text-gray-600'
            }`}>
              {impact.clawbackCount > 0
                ? <>미결 차이를 모두 승인하면 <b>{impact.clawbackCount}건 · {money(impact.totalClawbackAmount)}</b> 이 셀러에게서 회수됩니다.
                    {impact.noImpactCount > 0 && ` (회수 없는 차이 ${impact.noImpactCount}건 별도)`}</>
                : <>승인해도 회수가 발생하지 않는 차이 {impact.noImpactCount}건입니다.</>}
            </div>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['ID', '유형', '결제', 'PG 거래', '내부금액', 'PG금액', '차이', '상태', '처리', '사유', ''].map((h) => (
                    <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {detail.discrepancies.map((d) => (
                  <tr key={d.id} className={d.status === 'PENDING' ? 'bg-yellow-50/40' : ''}>
                    <td className="px-3 py-2 font-mono text-xs">{d.id}</td>
                    <td className="px-3 py-2 text-xs">{DISCREPANCY_TYPE_LABEL[d.type] ?? d.type}</td>
                    <td className="px-3 py-2 font-mono text-xs">{d.paymentId ?? '-'}</td>
                    <td className="px-3 py-2 font-mono text-xs text-gray-500">{d.pgTransactionId ?? '-'}</td>
                    <td className="px-3 py-2 text-right text-xs">{money(d.internalAmount)}</td>
                    <td className="px-3 py-2 text-right text-xs">{money(d.pgAmount)}</td>
                    <td className="px-3 py-2 text-right text-xs font-semibold text-red-700">{money(d.difference)}</td>
                    <td className="px-3 py-2">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${DISCREPANCY_STATUS_STYLE[d.status] ?? 'bg-gray-100'}`}>
                        {d.status}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-xs text-gray-500">
                      {d.resolvedBy ? `${d.resolvedBy} · ${dateTime(d.resolvedAt)}` : '-'}
                    </td>
                    <td className="px-3 py-2 text-xs text-gray-500 max-w-[12rem] truncate" title={d.note ?? ''}>
                      {d.note ?? '-'}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      {d.status === 'PENDING' && !detail.run.closed && (
                        <>
                          <button
                            onClick={() => void resolve(d, 'approve')}
                            disabled={busyId !== null}
                            className="text-xs px-2 py-1 mr-1 rounded bg-gray-900 text-white hover:bg-gray-800 disabled:opacity-40"
                          >
                            승인
                          </button>
                          <button
                            onClick={() => void resolve(d, 'reject')}
                            disabled={busyId !== null}
                            className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                          >
                            거절
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
                {detail.discrepancies.length === 0 && (
                  <tr><td colSpan={11} className="px-3 py-8 text-center text-gray-400">차이가 없습니다 — 전부 일치했습니다.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
};

export default PgReconciliationConsolePage;
