import React, { useCallback, useEffect, useState } from 'react';
import {
  payoutApi,
  Payout,
  PayoutPreview,
  PayoutStatus,
  PAYOUT_STATUS_LABEL,
  newIdempotencyKey,
} from '@/api/payout';
import { settlementApi, type HoldbackReleasePreview } from '@/api/settlement';
import {
  sellerBankAccountApi,
  type SellerBankAccountInput,
  type SellerBankAccountView,
} from '@/api/sellerBankAccount';
import SellerBankAccountForm from '@/components/SellerBankAccountForm';
import Spinner from '@/components/Spinner';
import { errorDetail, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtDate = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

const statusClass = (status: PayoutStatus): string => {
  switch (status) {
    case 'COMPLETED':
      return 'bg-green-100 text-green-800';
    case 'FAILED':
      return 'bg-red-100 text-red-800';
    case 'CANCELED':
      return 'bg-gray-200 text-gray-700';
    case 'SENDING':
      return 'bg-blue-100 text-blue-800';
    default:
      return 'bg-yellow-100 text-yellow-800';
  }
};

type Tab = 'failed' | 'pending';

/* ─────────────────────────────────────────
   출금 1건
───────────────────────────────────────── */
const PayoutRow: React.FC<{
  payout: Payout;
  onChanged: (payout: Payout) => void;
  onBounced: () => void;
}> = ({ payout, onChanged, onBounced }) => {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'idle' | 'cancel' | 'bounce'>('idle');
  const [reason, setReason] = useState('');

  /**
   * 실자금 경로라 실패를 반드시 드러낸다. 409 는 "이미 처리된 요청"이므로
   * 오류가 아니라 중복 클릭이었다고 알려 준다 — 서버의 멱등 방어가 동작한 결과다.
   */
  const run = async (action: () => Promise<void>, successMsg: string) => {
    setBusy(true);
    try {
      await action();
      setMode('idle');
      setReason('');
      showToast(successMsg, 'success');
    } catch (err) {
      if (apiErrorStatus(err) === 409) {
        showToast('이미 처리된 요청입니다 (중복 방지).', 'warning');
      } else {
        showToast(errorDetail(err, '처리에 실패했습니다.'), 'error');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-bold text-gray-900">
            지급 #{payout.id} · 정산 #{payout.settlementId}
          </p>
          <p className="text-xs text-gray-400 mt-0.5">
            셀러 #{payout.sellerId} · 요청 {fmtDate(payout.requestedAt)}
            {payout.retryCount > 0 && ` · 재시도 ${payout.retryCount}회`}
          </p>
          <p className="text-base font-bold text-blue-700 mt-1">{fmt(payout.amount)}</p>
          <p className="text-xs text-gray-500 mt-1 font-mono">
            {payout.bank} {payout.account} ({payout.holder})
          </p>
          {payout.failureReason && (
            <p className="text-xs text-red-600 mt-1">실패 사유: {payout.failureReason}</p>
          )}
        </div>
        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${statusClass(payout.status)}`}>
          {PAYOUT_STATUS_LABEL[payout.status]}
        </span>
      </div>

      {mode !== 'idle' ? (
        <div className="mt-3">
          <label className="block text-xs font-medium text-gray-700 mb-1">
            {mode === 'cancel' ? '취소 사유 (감사 기록에 남습니다)' : '반송 사유'}
          </label>
          {mode === 'bounce' && (
            <p className="text-xs text-orange-700 bg-orange-50 border border-orange-200 rounded p-2 mb-2">
              계좌 정정을 <b>먼저</b> 하세요. 정정 없이 반송을 기록하면 같은 계좌로 재지급됩니다.
            </p>
          )}
          <input
            aria-label={mode === 'cancel' ? '취소 사유' : '반송 사유'}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="w-full border border-gray-300 rounded px-2 py-1.5 text-sm"
          />
          <div className="flex gap-2 mt-2">
            <button
              type="button"
              disabled={busy || reason.trim() === ''}
              onClick={() =>
                mode === 'cancel'
                  ? void run(
                      async () => onChanged(await payoutApi.cancel(payout.id, reason.trim(), newIdempotencyKey())),
                      '출금을 취소했습니다.'
                    )
                  : void run(async () => {
                      await payoutApi.bounce(payout.id, reason.trim(), newIdempotencyKey());
                      onBounced();
                    }, '반송을 기록하고 재지급을 요청했습니다.')
              }
              className="px-3 py-1.5 text-xs font-semibold rounded bg-red-600 text-white disabled:opacity-40"
            >
              {mode === 'cancel' ? '취소 확정' : '반송 기록'}
            </button>
            <button
              type="button"
              onClick={() => { setMode('idle'); setReason(''); }}
              className="px-3 py-1.5 text-xs rounded border border-gray-300 text-gray-600"
            >
              닫기
            </button>
          </div>
        </div>
      ) : (
        <div className="mt-3 flex flex-wrap gap-2">
          {payout.status === 'FAILED' && (
            <>
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  void run(
                    async () => onChanged(await payoutApi.retry(payout.id, newIdempotencyKey())),
                    '재시도로 등록했습니다.'
                  )
                }
                className="px-3 py-1.5 text-xs font-semibold rounded bg-gray-900 text-white disabled:opacity-40"
              >
                재시도
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => setMode('cancel')}
                className="px-3 py-1.5 text-xs font-medium rounded border border-red-300 text-red-700 disabled:opacity-40"
              >
                영구 취소
              </button>
            </>
          )}
          {payout.status === 'COMPLETED' && (
            <button
              type="button"
              disabled={busy}
              onClick={() => setMode('bounce')}
              className="px-3 py-1.5 text-xs font-medium rounded border border-orange-300 text-orange-700 disabled:opacity-40"
            >
              반송 기록
            </button>
          )}
        </div>
      )}
    </div>
  );
};

/* ─────────────────────────────────────────
   홀드백 해제 미리보기
───────────────────────────────────────── */

/**
 * 그날 무엇이 얼마나 풀려 지급 대상이 되는지 미리 본다.
 *
 * <p>이 패널에는 <b>실행 버튼이 없다.</b> 서버 엔드포인트가 조회 전용이고, 실제 해제는 배치나
 * 재실행 콘솔의 몫이다. 여기에 실행 버튼을 놓으면 "미리보기 화면에서 눌렀을 뿐인데 지급이
 * 나갔다"가 가능해진다 — 조회 화면과 집행 화면을 섞지 않는다.
 *
 * <p>날짜를 미래로 둘 수 있게 열어 둔 이유는 자금 계획이다. 오늘 풀릴 것만 볼 수 있으면
 * "다음 주에 얼마가 필요한가"에 답할 수 없다.
 */
const HoldbackPreviewPanel: React.FC = () => {
  const today = new Date().toISOString().slice(0, 10);
  const [date, setDate] = useState(today);
  const [preview, setPreview] = useState<HoldbackReleasePreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setPreview(await settlementApi.holdbackPreview(date));
    } catch (err) {
      setError(errorDetail(err, '홀드백 미리보기를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 mb-4 space-y-3"
      data-testid="holdback-preview">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900">홀드백 해제 미리보기</h2>
          <p className="text-sm text-gray-500 mt-1">
            조회 전용입니다 — 이 화면에서는 아무것도 해제되지 않습니다.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)}
            aria-label="해제 기준일"
            className="rounded border border-gray-300 px-2 py-1.5 text-sm" />
          <button type="button" onClick={() => void load()} disabled={loading}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 text-gray-700 bg-white disabled:opacity-50">
            {loading ? '조회 중…' : '조회'}
          </button>
        </div>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {preview && (
        <div className="space-y-2">
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="rounded bg-gray-50 p-3">
              <p className="text-xs text-gray-500">해제 예정</p>
              <p className="text-lg font-bold text-gray-900">{preview.count}건</p>
            </div>
            <div className="rounded bg-gray-50 p-3">
              <p className="text-xs text-gray-500">해제 금액</p>
              <p className="text-lg font-bold text-gray-900">{fmt(preview.totalAmount)}</p>
            </div>
          </div>

          {/* 잘렸다는 사실을 숨기면 운영자가 목록 길이를 전체 규모로 읽는다. */}
          {preview.truncated && (
            <p role="status" className="text-sm text-yellow-800 bg-yellow-50 rounded px-3 py-2">
              조회 한도까지 가득 찼습니다 — 위 건수·금액은 <b>전체가 아닙니다</b>.
            </p>
          )}

          {preview.lines.length > 0 && (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-1 pr-4">정산</th>
                    <th className="py-1 pr-4">결제</th>
                    <th className="py-1 pr-4">홀드백</th>
                    <th className="py-1">해제일</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.lines.map((line) => (
                    <tr key={line.settlementId} className="border-t border-gray-100">
                      <td className="py-1 pr-4">#{line.settlementId}</td>
                      <td className="py-1 pr-4">#{line.paymentId}</td>
                      <td className="py-1 pr-4">{fmt(line.holdbackAmount)}</td>
                      <td className="py-1">{line.releaseDate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {preview.count === 0 && (
            <p className="text-sm text-gray-500">이 날짜에 풀릴 홀드백이 없습니다.</p>
          )}
        </div>
      )}
    </section>
  );
};

/* ─────────────────────────────────────────
   송금 미리보기 + 즉시 실행
───────────────────────────────────────── */
const PreviewPanel: React.FC<{ onExecuted: () => void }> = ({ onExecuted }) => {
  const { showToast } = useToast();
  const [preview, setPreview] = useState<PayoutPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [executing, setExecuting] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setPreview(await payoutApi.preview());
    } catch (err) {
      showToast(errorDetail(err, '미리보기를 불러오지 못했습니다.'), 'error');
    } finally {
      setLoading(false);
    }
  };

  const execute = async () => {
    // 되돌리기 어려운 외부 송금이다. 규모를 눈으로 확인시키고 한 번 더 묻는다.
    const summary = preview
      ? `${preview.sendableCount}건 / ${fmt(preview.sendableAmount)}`
      : '대기 중인 전체 건';
    if (!window.confirm(`실제 송금을 실행합니다.\n\n대상: ${summary}\n\n계속하시겠습니까?`)) return;
    setExecuting(true);
    try {
      const report = await payoutApi.executeNow();
      showToast(
        `송금 완료 ${report.succeeded}건 · 실패 ${report.failed}건 · 한도로 보류 ${report.limitedSkipped}건`,
        report.failed > 0 ? 'warning' : 'success'
      );
      onExecuted();
      await load();
    } catch (err) {
      showToast(errorDetail(err, '송금 실행에 실패했습니다.'), 'error');
    } finally {
      setExecuting(false);
    }
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 mb-5">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-bold text-gray-900">송금 미리보기</h2>
          <p className="text-xs text-gray-400 mt-0.5">
            상태를 바꾸지 않습니다. 실행 전 규모와 밀린 사유를 확인하세요.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          disabled={loading}
          className="px-3 py-1.5 text-sm font-semibold rounded border border-gray-300 text-gray-700 disabled:opacity-40"
        >
          {loading ? '불러오는 중...' : '미리보기'}
        </button>
      </div>

      {preview && (
        <>
          <div className="grid grid-cols-2 gap-3 mt-3">
            <div className="rounded-lg bg-blue-50 border border-blue-200 p-3">
              <p className="text-xs text-blue-700">송금 가능</p>
              <p className="text-lg font-bold text-blue-900">{preview.sendableCount}건</p>
              <p className="text-sm text-blue-800">{fmt(preview.sendableAmount)}</p>
            </div>
            <div className="rounded-lg bg-yellow-50 border border-yellow-200 p-3">
              <p className="text-xs text-yellow-700">한도로 보류</p>
              <p className="text-lg font-bold text-yellow-900">{preview.limitedCount}건</p>
              <p className="text-sm text-yellow-800">{fmt(preview.limitedAmount)}</p>
            </div>
          </div>

          {preview.lines.length > 0 && (
            <div className="mt-3 max-h-56 overflow-y-auto border border-gray-200 rounded-lg">
              <table className="w-full text-xs">
                <thead className="bg-gray-50 sticky top-0">
                  <tr>
                    <th className="text-left px-2 py-1.5 font-semibold text-gray-600">지급</th>
                    <th className="text-left px-2 py-1.5 font-semibold text-gray-600">셀러</th>
                    <th className="text-right px-2 py-1.5 font-semibold text-gray-600">금액</th>
                    <th className="text-left px-2 py-1.5 font-semibold text-gray-600">상태</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.lines.map((line) => (
                    <tr key={line.payoutId} className="border-t border-gray-100">
                      <td className="px-2 py-1.5 text-gray-700">#{line.payoutId}</td>
                      <td className="px-2 py-1.5 text-gray-700">#{line.sellerId}</td>
                      <td className="px-2 py-1.5 text-right text-gray-900">{fmt(line.amount)}</td>
                      <td className="px-2 py-1.5">
                        {line.sendable ? (
                          <span className="text-green-700">송금 가능</span>
                        ) : (
                          <span className="text-yellow-700">{line.reason ?? '보류'}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <button
            type="button"
            onClick={() => void execute()}
            disabled={executing}
            className="mt-3 w-full px-3 py-2 text-sm font-bold rounded bg-red-600 text-white disabled:opacity-40"
          >
            {executing ? '송금 실행 중...' : '지금 송금 실행'}
          </button>
        </>
      )}
    </div>
  );
};

/* ─────────────────────────────────────────
   지급 콘솔
───────────────────────────────────────── */
/**
 * 셀러 지급 계좌 레지스트리 — 이 콘솔 안에 있는 이유.
 *
 * <p>반송(bounce) 처리의 <b>선행 조건</b>이 계좌 정정이다. 반송을 기록하면 서버가 정정된 계좌로
 * 새 송금을 재발행하는데, 계좌를 안 고치고 반송만 기록하면 같은 틀린 계좌로 또 나간다.
 * 안내는 있는데 고칠 화면이 없어서 그동안 DB 를 직접 고쳐야 했다.
 *
 * <p>계좌 미등록은 <b>실패로도 안 보인다</b>: payout 이 아예 생성되지 않아 아래 실패·대기 목록
 * 어디에도 뜨지 않는다. 그래서 셀러 번호로 직접 조회하는 입구가 필요하다.
 */
const SellerBankAccountPanel: React.FC = () => {
  const { showToast } = useToast();
  const [sellerInput, setSellerInput] = useState('');
  /** 조회를 끝낸 셀러. 입력칸이 아니라 <b>이 값</b>으로 저장한다. */
  const [loadedSellerId, setLoadedSellerId] = useState<number | null>(null);
  const [account, setAccount] = useState<SellerBankAccountView | null>(null);
  const [looking, setLooking] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parsedSellerId = Number(sellerInput.trim());
  const validSellerId = sellerInput.trim() !== '' && Number.isInteger(parsedSellerId) && parsedSellerId > 0;

  /**
   * 셀러 번호를 고치면 조회 결과를 버린다. 안 버리면 "A 를 조회해 놓고 B 로 바꾼 뒤 저장"이
   * 가능해지는데, 화면에는 여전히 A 의 계좌가 떠 있어 조작자는 A 를 고쳤다고 믿는다.
   */
  const changeSeller = (value: string) => {
    setSellerInput(value);
    setLoadedSellerId(null);
    setAccount(null);
    setError(null);
  };

  const lookup = async () => {
    if (!validSellerId) return;
    setLooking(true);
    setError(null);
    try {
      setAccount(await sellerBankAccountApi.of(parsedSellerId));
      setLoadedSellerId(parsedSellerId);
    } catch (err) {
      setError(errorDetail(err, '셀러 계좌를 조회하지 못했습니다.'));
    } finally {
      setLooking(false);
    }
  };

  const save = async (input: SellerBankAccountInput) => {
    if (loadedSellerId === null) return;
    setSaving(true);
    setError(null);
    try {
      const saved = await sellerBankAccountApi.save(loadedSellerId, input);
      setAccount(saved);
      showToast(`셀러 ${loadedSellerId} 계좌를 저장했습니다 (${saved.bank} ${saved.account}).`, 'success');
    } catch (err) {
      setError(errorDetail(err, '셀러 계좌를 저장하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 mb-4 space-y-3"
      data-testid="seller-bank-account-panel">
      <div>
        <h2 className="font-semibold text-gray-900">셀러 지급 계좌</h2>
        <p className="text-sm text-gray-500 mt-1">
          계좌가 없으면 정산이 확정돼도 송금이 <b>만들어지지 않습니다</b> — 아래 실패·대기 목록에도
          뜨지 않으니 셀러 번호로 직접 확인하세요. 반송 처리 전에는 계좌 정정이 먼저입니다.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <label className="text-sm" htmlFor="sba-seller-id">
          <span className="text-gray-600">셀러 번호</span>
          <input id="sba-seller-id" value={sellerInput} inputMode="numeric"
            onChange={(e) => changeSeller(e.target.value)}
            className="mt-1 block w-40 rounded border px-3 py-2 font-mono" />
        </label>
        {/* 이름이 그냥 '조회'면 홀드백 패널의 조회 버튼과 구분되지 않는다 —
            보이는 화면에서는 위치로 알 수 있지만 스크린리더에는 같은 버튼 둘이다. */}
        <button type="button" onClick={() => void lookup()} disabled={!validSellerId || looking}
          className="rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700 disabled:opacity-50">
          {looking ? '조회 중…' : '계좌 조회'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {loadedSellerId !== null && (
        <div className="space-y-3 border-t pt-3" data-testid="sba-result">
          {account ? (
            <p className="text-sm" data-testid="sba-current">
              셀러 <b>{loadedSellerId}</b> · <span className="font-mono">{account.bank} {account.account}</span>
              {' '}· 예금주 {account.holder}
            </p>
          ) : (
            <p role="alert" className="text-sm text-amber-800" data-testid="sba-missing">
              셀러 <b>{loadedSellerId}</b> 는 <b>등록된 계좌가 없습니다.</b> 이 셀러의 정산금은
              지금 지급되지 않고 있습니다.
            </p>
          )}

          <SellerBankAccountForm current={account} saving={saving}
            onSubmit={(input) => void save(input)} idPrefix="admin-sba" />
        </div>
      )}
    </section>
  );
};

const PayoutAdminPage: React.FC = () => {
  const [tab, setTab] = useState<Tab>('failed');
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPayouts(tab === 'failed' ? await payoutApi.listFailed(50) : await payoutApi.listPending(50));
    } catch (err) {
      setError(errorDetail(err, '출금 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleChanged = (updated: Payout) => {
    // 재시도·취소로 상태가 바뀌면 현재 탭의 조건에서 벗어난다 — 목록에서 덜어낸다.
    setPayouts((prev) =>
      prev
        .map((p) => (p.id === updated.id ? updated : p))
        .filter((p) => (tab === 'failed' ? p.status === 'FAILED' : p.status === 'REQUESTED'))
    );
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">정산 지급 콘솔</h1>
            <p className="text-sm text-gray-500 mt-1">
              실자금 송금입니다. 모든 조작은 감사 기록에 남고, 중복 요청은 서버가 막습니다.
            </p>
          </div>
          <button
            onClick={() => void load()}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 text-gray-700 bg-white"
          >
            새로고침
          </button>
        </div>

        <SellerBankAccountPanel />

        <PreviewPanel onExecuted={() => void load()} />

        <HoldbackPreviewPanel />

        <div className="flex bg-white rounded-xl border border-gray-200 p-1 mb-4">
          {([
            { id: 'failed', label: '지급 실패' },
            { id: 'pending', label: '지급 대기' },
          ] as { id: Tab; label: string }[]).map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`flex-1 py-2 text-sm font-semibold rounded-lg transition-all ${
                tab === t.id ? 'bg-gray-900 text-white' : 'text-gray-500 hover:text-gray-800'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        {loading ? (
          <Spinner size="md" message="출금 목록 불러오는 중..." />
        ) : error ? (
          <p className="text-center text-red-600 py-8">{error}</p>
        ) : payouts.length === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
            <p className="text-sm">
              {tab === 'failed' ? '지급 실패 건이 없습니다.' : '지급 대기 건이 없습니다.'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {payouts.map((p) => (
              <PayoutRow key={p.id} payout={p} onChanged={handleChanged} onBounced={() => void load()} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default PayoutAdminPage;
