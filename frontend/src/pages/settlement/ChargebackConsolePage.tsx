import React, { useCallback, useEffect, useState } from 'react';
import {
  chargebackApi,
  CHARGEBACK_REASON_LABEL,
  CHARGEBACK_STATUS_LABEL,
  type Chargeback,
  type ChargebackReason,
  type ChargebackStatus,
} from '@/api/chargeback';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 카드사 분쟁(차지백) 콘솔 — ADMIN 전용 표면.
 *
 * <p>수락은 셀러 정산금에서 차감하는 결정이고, 기각은 분쟁을 종결하되 정산에는 손대지 않는다.
 * 둘 다 되돌리는 API 가 없으므로(상태가 OPEN 을 떠나면 끝) 확인창에서 <b>어느 쪽이 돈을
 * 움직이는지</b>를 분명히 말해 준다. 실수 방향이 비대칭이기 때문이다 — 잘못된 수락은 셀러
 * 돈을 부당하게 걷고, 잘못된 기각은 회사가 손실을 떠안는다.
 */

const STATUS_STYLE: Record<ChargebackStatus, string> = {
  OPEN: 'bg-yellow-100 text-yellow-800',
  ACCEPTED: 'bg-red-100 text-red-800',
  REJECTED: 'bg-gray-200 text-gray-600',
};

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

const STATUSES: ChargebackStatus[] = ['OPEN', 'ACCEPTED', 'REJECTED'];
const REASONS = Object.keys(CHARGEBACK_REASON_LABEL) as ChargebackReason[];

const ChargebackConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [status, setStatus] = useState<ChargebackStatus>('OPEN');
  const [rows, setRows] = useState<Chargeback[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const [openForm, setOpenForm] = useState(false);
  const [form, setForm] = useState({
    paymentId: '', settlementId: '', amount: '',
    reasonCode: 'FRAUD' as ChargebackReason, reasonDetail: '',
  });
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await chargebackApi.list(status));
    } catch (err) {
      setRows([]);
      setError(apiErrorMessage(err, '분쟁 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => { void load(); }, [load]);

  const decide = useCallback(async (cb: Chargeback, action: 'accept' | 'reject') => {
    const isAccept = action === 'accept';
    const message = isAccept
      ? `분쟁 #${cb.id} 을 수락합니다.\n`
        + `셀러 정산금에서 ${money(cb.amount)} 이 차감됩니다(역정산 생성). 되돌릴 수 없습니다.\n진행할까요?`
      : `분쟁 #${cb.id} 을 기각합니다.\n`
        + '셀러 증빙을 인정해 분쟁을 종결하며 정산에는 영향이 없습니다. 사유가 필요합니다.\n진행할까요?';

    if (!window.confirm(message)) return;

    const note = window.prompt(isAccept ? '수락 사유(선택)' : '기각 사유(필수)', '') ?? '';
    if (!isAccept && note.trim() === '') {
      showToast('기각에는 사유가 필요합니다.', 'warning');
      return;
    }

    setBusyId(cb.id);
    try {
      if (isAccept) await chargebackApi.accept(cb.id, note);
      else await chargebackApi.reject(cb.id, note);
      showToast(`분쟁 #${cb.id} 을 ${isAccept ? '수락' : '기각'}했습니다.`, 'success');
      await load();
    } catch (err) {
      // 409 = 같은 결정이 이미 접수됨(멱등키 선점). 실패가 아니라 "이미 됐다"이므로 다르게 안내한다.
      if (apiErrorStatus(err) === 409) {
        showToast('이미 처리된 결정입니다. 목록을 갱신합니다.', 'warning');
        await load();
      } else {
        showToast(apiErrorMessage(err, '처리에 실패했습니다.'), 'error');
      }
    } finally {
      setBusyId(null);
    }
  }, [load, showToast]);

  const submitOpen = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    const paymentId = Number(form.paymentId);
    const settlementId = Number(form.settlementId);
    if (!Number.isFinite(paymentId) || paymentId <= 0 || !Number.isFinite(settlementId) || settlementId <= 0) {
      showToast('결제 ID·정산 ID 를 확인하세요.', 'warning');
      return;
    }
    if (Number(form.amount) <= 0) {
      showToast('분쟁 금액은 0보다 커야 합니다.', 'warning');
      return;
    }
    setSaving(true);
    try {
      const created = await chargebackApi.open({
        paymentId, settlementId, amount: form.amount,
        reasonCode: form.reasonCode,
        reasonDetail: form.reasonDetail.trim() || undefined,
      });
      showToast(`분쟁 #${created.id} 을 등록했습니다.`, 'success');
      setOpenForm(false);
      setForm({ paymentId: '', settlementId: '', amount: '', reasonCode: 'FRAUD', reasonDetail: '' });
      setStatus('OPEN');
      await load();
    } catch (err) {
      showToast(apiErrorMessage(err, '등록에 실패했습니다.'), 'error');
    } finally {
      setSaving(false);
    }
  }, [form, load, showToast]);

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
        <div>
          <h2 className="text-xl font-bold text-gray-900">차지백</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            카드사 분쟁을 처리합니다. 수락하면 셀러 정산금에서 차감되고, 기각하면 정산에 영향 없이 종결됩니다.
          </p>
        </div>
        <button
          onClick={() => setOpenForm((v) => !v)}
          className="px-4 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50"
        >
          {openForm ? '등록 취소' : '수동 등록'}
        </button>
      </div>

      {/* ── 수동 등록 — PG 통지가 누락된 분쟁을 운영자가 직접 연다 ───────── */}
      {openForm && (
        <form onSubmit={submitOpen} className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
          <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
            <label className="block">
              <span className="text-[11px] text-gray-500">결제 ID</span>
              <input value={form.paymentId} onChange={(e) => setForm((f) => ({ ...f, paymentId: e.target.value }))}
                className="input font-mono" inputMode="numeric" />
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">정산 ID</span>
              <input value={form.settlementId} onChange={(e) => setForm((f) => ({ ...f, settlementId: e.target.value }))}
                className="input font-mono" inputMode="numeric" />
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">금액</span>
              <input value={form.amount} onChange={(e) => setForm((f) => ({ ...f, amount: e.target.value }))}
                className="input font-mono" inputMode="decimal" />
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">사유 코드</span>
              <select value={form.reasonCode}
                onChange={(e) => setForm((f) => ({ ...f, reasonCode: e.target.value as ChargebackReason }))}
                className="input">
                {REASONS.map((r) => <option key={r} value={r}>{CHARGEBACK_REASON_LABEL[r]}</option>)}
              </select>
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">상세</span>
              <input value={form.reasonDetail} onChange={(e) => setForm((f) => ({ ...f, reasonDetail: e.target.value }))}
                className="input" placeholder="카드사 통지 내용" />
            </label>
          </div>
          <div className="mt-3">
            <button type="submit" disabled={saving}
              className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40">
              {saving ? '등록 중…' : '분쟁 등록'}
            </button>
          </div>
        </form>
      )}

      {/* ── 목록 ───────────────────────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4">
        <div className="flex items-center gap-2 mb-4">
          {STATUSES.map((s) => (
            <button
              key={s}
              onClick={() => setStatus(s)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                status === s ? 'bg-gray-900 text-white' : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {CHARGEBACK_STATUS_LABEL[s]}
            </button>
          ))}
          <button onClick={() => void load()} disabled={loading}
            className="ml-auto text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40">
            새로고침
          </button>
        </div>

        {error && <p className="text-sm text-orange-700 mb-2">{error}</p>}
        {loading && <div className="flex justify-center py-6"><Spinner /></div>}

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['ID', '결제', '정산', '금액', '사유', '상태', '출처', '접수', '결정', ''].map((h) => (
                  <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.map((cb) => (
                <tr key={cb.id} className="hover:bg-gray-50">
                  <td className="px-3 py-2 font-mono text-xs">{cb.id}</td>
                  <td className="px-3 py-2 font-mono text-xs">{cb.paymentId ?? '-'}</td>
                  <td className="px-3 py-2 font-mono text-xs">{cb.settlementId ?? '-'}</td>
                  <td className="px-3 py-2 text-right whitespace-nowrap font-semibold">{money(cb.amount)}</td>
                  <td className="px-3 py-2 text-xs" title={cb.reasonDetail ?? ''}>
                    {CHARGEBACK_REASON_LABEL[cb.reasonCode] ?? cb.reasonCode}
                  </td>
                  <td className="px-3 py-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${STATUS_STYLE[cb.status]}`}>
                      {cb.status}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-xs text-gray-500">{cb.source}</td>
                  <td className="px-3 py-2 text-xs text-gray-500">{dateTime(cb.raisedAt)}</td>
                  <td className="px-3 py-2 text-xs text-gray-500">
                    {cb.decidedBy ? `${cb.decidedBy} · ${dateTime(cb.decidedAt)}` : '-'}
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap">
                    {cb.status === 'OPEN' && (
                      <>
                        <button
                          onClick={() => void decide(cb, 'accept')}
                          disabled={busyId !== null}
                          className="text-xs px-2 py-1 mr-1 rounded bg-red-700 text-white hover:bg-red-800 disabled:opacity-40"
                        >
                          수락
                        </button>
                        <button
                          onClick={() => void decide(cb, 'reject')}
                          disabled={busyId !== null}
                          className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                        >
                          기각
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={10} className="px-3 py-8 text-center text-gray-400">
                    {CHARGEBACK_STATUS_LABEL[status]} 분쟁이 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
};

export default ChargebackConsolePage;
