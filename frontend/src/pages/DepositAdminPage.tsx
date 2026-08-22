import React, { useCallback, useEffect, useState } from 'react';
import {
  depositApi,
  depositAdminApi,
  type DepositAccount,
  type DepositHolderType,
  type DepositShortfall,
} from '@/api/deposit';
// errorDetail 이 아니라 apiErrorMessage 다. errorDetail 은 예외 자체의 message 까지 화면에
// 올리는데, 그건 서버 응답이 아예 없는 경로(결제창 SDK 등) 전용이다. 이 콘솔은 평범한 REST 라
// 그걸 쓰면 네트워크 장애에 "Network Error" 같은 원문이 운영자에게 그대로 노출된다.
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 예치금 운영 콘솔 — 잔고를 움직이는 유일한 수기 경로이자, 부족분을 해소하는 유일한 경로.
 *
 * <p><b>왜 화면이 필요한가.</b> 입금·출금은 Kafka 컨슈머가 자동 처리하지만, 선점(hold)·상계(offset)의
 * 자동 트리거인 card 이벤트에는 {@code sellerId} 가 없어 대상 계좌를 특정할 수 없다. 그래서 서버가
 * "현재 잔고 변동 입력은 수기 콘솔 경로뿐"이라고 적어 둔 상태다. 부족분은 더하다 — 도메인 주석이
 * "해소 주체가 아직 없다"고 명시하고, {@code resolve}/{@code writeOff} 는 프로덕션 호출자가 0건이며
 * OPEN 건을 도는 스케줄러도 없다. <b>이 화면이 없는 동안 부족분은 쌓이기만 했다.</b>
 *
 * <p><b>조작 전에 잔고를 먼저 보여 준다.</b> 잔고를 모르고 출금·상계를 누르는 것은 눈 감고 돈을
 * 옮기는 것이다. 그래서 셀러를 조회하기 전에는 조작 폼 자체를 그리지 않는다.
 *
 * <p><b>멱등 키를 화면이 지어내지 않는다.</b> 원장의 L3 방어선이
 * {@code UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)} 인데,
 * 화면이 매 제출마다 새 키를 만들면 그 UNIQUE 는 절대 걸리지 않는다 — 중복 방어가 사라진 채로
 * 안전해 보이기만 한다. 그래서 운영자가 업무 참조(티켓 번호 등)를 직접 적는다.
 */

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtDate = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }) : '-';

const HOLDER_TYPES: { value: DepositHolderType; label: string }[] = [
  { value: 'CARD_AUTHORIZATION', label: '카드 승인' },
  { value: 'LOAN_DISBURSEMENT', label: '대출 실행' },
  { value: 'INVESTMENT_EXECUTION', label: '투자 집행' },
];

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const buttonClass = 'rounded px-4 py-2 text-sm font-semibold disabled:opacity-50';

const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <label className="block text-sm">
      <span className="text-gray-600">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </label>
  );

// ────────────────────────────────────────────────────────────────────────────
// 잔고 조작
// ────────────────────────────────────────────────────────────────────────────

const BalancePanel: React.FC<{ onShortfallMaybeChanged: () => void }> = ({ onShortfallMaybeChanged }) => {
  const { showToast } = useToast();
  const [sellerInput, setSellerInput] = useState('');
  /** 조회를 마친 셀러. 입력칸이 아니라 <b>이 값</b>으로 조작한다. */
  const [sellerId, setSellerId] = useState<number | null>(null);
  const [account, setAccount] = useState<DepositAccount | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 입출금
  const [amount, setAmount] = useState('');
  const [referenceType, setReferenceType] = useState('');
  const [referenceId, setReferenceId] = useState('');
  // 선점
  const [holdType, setHoldType] = useState<DepositHolderType>('CARD_AUTHORIZATION');
  const [holdRef, setHoldRef] = useState('');
  const [holdAmount, setHoldAmount] = useState('');
  const [holdExpires, setHoldExpires] = useState('');
  // 상계
  const [offsetType, setOffsetType] = useState<DepositHolderType>('CARD_AUTHORIZATION');
  const [offsetRef, setOffsetRef] = useState('');
  const [offsetAmount, setOffsetAmount] = useState('');
  const [offsetSequence, setOffsetSequence] = useState('0');

  const parsed = Number(sellerInput.trim());
  const validSeller = sellerInput.trim() !== '' && Number.isInteger(parsed) && parsed > 0;

  /** 셀러 번호를 고치면 조회 결과와 조작 폼을 버린다 — "A 를 보고 B 를 조작"을 만들지 않는다. */
  const changeSeller = (value: string) => {
    setSellerInput(value);
    setSellerId(null);
    setAccount(null);
    setError(null);
  };

  const lookup = async () => {
    if (!validSeller) return;
    setBusy(true);
    setError(null);
    try {
      setAccount(await depositApi.accountOf(parsed));
      setSellerId(parsed);
    } catch (err) {
      setError(apiErrorMessage(err, '예치 계좌를 조회하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  /** 조작 후에는 반드시 잔고를 다시 읽는다 — 화면의 숫자가 조작 전 값으로 남으면 다음 판단이 틀어진다. */
  const refresh = async (id: number) => {
    try {
      setAccount(await depositApi.accountOf(id));
    } catch {
      // 조작 자체는 성공했다. 갱신 실패로 성공 메시지를 지우지 않는다.
      setAccount(null);
    }
  };

  const run = async (label: string, action: () => Promise<string>) => {
    if (sellerId === null) return;
    setBusy(true);
    setError(null);
    try {
      showToast(await action(), 'success');
      await refresh(sellerId);
      onShortfallMaybeChanged();
    } catch (err) {
      setError(apiErrorMessage(err, `${label} 처리에 실패했습니다.`));
    } finally {
      setBusy(false);
    }
  };

  const entryFilled = amount.trim() !== '' && Number(amount) > 0
    && referenceType.trim() !== '' && referenceId.trim() !== '';

  const entryInput = () => ({
    amount: Number(amount),
    referenceType: referenceType.trim(),
    referenceId: referenceId.trim(),
  });

  const clearEntry = () => { setAmount(''); setReferenceType(''); setReferenceId(''); };

  const credit = () => void run('입금', async () => {
    await depositAdminApi.credit(sellerId!, entryInput());
    clearEntry();
    return `셀러 ${sellerId} 에 ${fmt(Number(amount))} 입금을 접수했습니다.`;
  });

  const debit = () => {
    if (!window.confirm(
      `셀러 ${sellerId} 의 예치금에서 ${fmt(Number(amount))} 를 차감합니다.\n\n`
      + `참조: ${referenceType.trim()} / ${referenceId.trim()}\n\n계속하시겠습니까?`)) return;
    void run('출금', async () => {
      await depositAdminApi.debit(sellerId!, entryInput());
      clearEntry();
      return `셀러 ${sellerId} 에서 ${fmt(Number(amount))} 출금을 접수했습니다.`;
    });
  };

  const holdFilled = holdRef.trim() !== '' && holdAmount.trim() !== '' && Number(holdAmount) > 0;

  const placeHold = () => void run('선점', async () => {
    const hold = await depositAdminApi.placeHold(sellerId!, {
      holderType: holdType,
      holderReference: holdRef.trim(),
      amount: Number(holdAmount),
      ...(holdExpires ? { expiresAt: holdExpires } : {}),
    });
    setHoldRef(''); setHoldAmount(''); setHoldExpires('');
    return `선점 #${hold.id} — 잔여 ${fmt(hold.remainingAmount)} (만료 ${fmtDate(hold.expiresAt)})`;
  });

  const offsetFilled = offsetRef.trim() !== '' && offsetAmount.trim() !== '' && Number(offsetAmount) > 0
    && offsetSequence.trim() !== '' && Number.isInteger(Number(offsetSequence)) && Number(offsetSequence) >= 0;

  const applyOffset = () => {
    if (!window.confirm(
      `셀러 ${sellerId} 에 ${fmt(Number(offsetAmount))} 상계를 겁니다.\n\n`
      + '잔고가 모자라도 실패하지 않고 부족분이 기록됩니다 — 그 부족분은 자동으로 해소되지 않습니다.\n\n'
      + '계속하시겠습니까?')) return;
    void run('상계', async () => {
      await depositAdminApi.applyOffset(sellerId!, {
        holderType: offsetType,
        holderReference: offsetRef.trim(),
        offsetAmount: Number(offsetAmount),
        offsetSequence: Number(offsetSequence),
      });
      setOffsetRef(''); setOffsetAmount('');
      return '상계를 접수했습니다. 잔고가 모자랐다면 아래 부족분 목록에 나타납니다.';
    });
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 mb-4 space-y-4"
      data-testid="deposit-balance-panel">
      <div>
        <h2 className="font-semibold text-gray-900">셀러 잔고 조작</h2>
        <p className="text-sm text-gray-500 mt-1">
          선점·상계는 카드 이벤트에 셀러 식별자가 없어 자동화되지 않았습니다 — 지금은 이 화면이
          유일한 입력 경로입니다. 조작 전에 반드시 잔고를 확인하세요.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <Field label="셀러 번호">
          <input aria-label="셀러 번호" value={sellerInput} inputMode="numeric"
            onChange={(e) => changeSeller(e.target.value)}
            className="mt-1 block w-40 rounded border px-3 py-2 font-mono" />
        </Field>
        <button type="button" onClick={() => void lookup()} disabled={!validSeller || busy}
          className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>
          잔고 조회
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {sellerId !== null && (
        <div className="space-y-4 border-t pt-4" data-testid="deposit-ops">
          {account ? (
            <dl className="flex flex-wrap gap-6 rounded bg-gray-50 p-3 text-sm"
              data-testid="deposit-balance">
              <div><dt className="text-gray-500">사용 가능</dt>
                <dd className="text-lg font-bold" data-testid="bal-available">{fmt(account.available)}</dd></div>
              <div><dt className="text-gray-500">묶인 금액</dt>
                <dd className="text-lg font-bold" data-testid="bal-locked">{fmt(account.locked)}</dd></div>
              <div><dt className="text-gray-500">합계</dt>
                <dd className="text-lg font-bold" data-testid="bal-total">{fmt(account.total)}</dd></div>
            </dl>
          ) : (
            <p className="text-sm text-amber-800" data-testid="deposit-no-account">
              셀러 {sellerId} 는 아직 예치 계좌가 없습니다. 입금하면 계좌가 만들어지고,
              출금·선점·상계는 실패합니다.
            </p>
          )}

          {/* 입출금 */}
          <div className="space-y-2 rounded border p-3">
            <h3 className="text-sm font-semibold">입금 · 출금</h3>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field label="금액">
                <input aria-label="금액" value={amount} inputMode="numeric"
                  onChange={(e) => setAmount(e.target.value)} className={inputClass} />
              </Field>
              <Field label="참조 유형" hint="예: MANUAL_ADJUSTMENT">
                <input aria-label="참조 유형" value={referenceType}
                  onChange={(e) => setReferenceType(e.target.value)} className={inputClass} />
              </Field>
              <Field label="참조 번호" hint="원장 멱등 키 — 같은 조작엔 같은 값을 쓰세요">
                <input aria-label="참조 번호" value={referenceId}
                  onChange={(e) => setReferenceId(e.target.value)} className={`${inputClass} font-mono`} />
              </Field>
            </div>
            <p className="text-xs text-gray-500">
              참조 유형·번호는 편의 항목이 아니라 원장의 중복 방어 키입니다. 매번 새 값을 지어내면
              같은 요청을 두 번 보내도 DB 가 막지 못합니다.
            </p>
            <div className="flex gap-2">
              <button type="button" onClick={credit} disabled={!entryFilled || busy}
                className={`${buttonClass} bg-blue-600 text-white`}>입금</button>
              <button type="button" onClick={debit} disabled={!entryFilled || busy}
                className={`${buttonClass} bg-red-600 text-white`}>출금</button>
            </div>
          </div>

          {/* 선점 */}
          <div className="space-y-2 rounded border p-3">
            <h3 className="text-sm font-semibold">선점 (hold)</h3>
            <div className="grid gap-3 sm:grid-cols-4">
              <Field label="선점 주체">
                <select aria-label="선점 주체" value={holdType} className={inputClass}
                  onChange={(e) => setHoldType(e.target.value as DepositHolderType)}>
                  {HOLDER_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </Field>
              <Field label="선점 참조" hint="주체+참조가 멱등 키">
                <input aria-label="선점 참조" value={holdRef}
                  onChange={(e) => setHoldRef(e.target.value)} className={`${inputClass} font-mono`} />
              </Field>
              <Field label="선점 금액">
                <input aria-label="선점 금액" value={holdAmount} inputMode="numeric"
                  onChange={(e) => setHoldAmount(e.target.value)} className={inputClass} />
              </Field>
              <Field label="만료" hint="비우면 72시간">
                <input aria-label="만료" type="datetime-local" value={holdExpires}
                  onChange={(e) => setHoldExpires(e.target.value)} className={inputClass} />
              </Field>
            </div>
            <button type="button" onClick={placeHold} disabled={!holdFilled || busy}
              className={`${buttonClass} bg-gray-800 text-white`}>선점 걸기</button>
          </div>

          {/* 상계 */}
          <div className="space-y-2 rounded border p-3">
            <h3 className="text-sm font-semibold">상계 (offset)</h3>
            <div className="grid gap-3 sm:grid-cols-4">
              <Field label="상계 주체">
                <select aria-label="상계 주체" value={offsetType} className={inputClass}
                  onChange={(e) => setOffsetType(e.target.value as DepositHolderType)}>
                  {HOLDER_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </Field>
              <Field label="상계 참조">
                <input aria-label="상계 참조" value={offsetRef}
                  onChange={(e) => setOffsetRef(e.target.value)} className={`${inputClass} font-mono`} />
              </Field>
              <Field label="상계 금액">
                <input aria-label="상계 금액" value={offsetAmount} inputMode="numeric"
                  onChange={(e) => setOffsetAmount(e.target.value)} className={inputClass} />
              </Field>
              <Field label="분할 회차" hint="같은 번호 재전송은 중복으로 막힙니다">
                <input aria-label="분할 회차" value={offsetSequence} inputMode="numeric"
                  onChange={(e) => setOffsetSequence(e.target.value)} className={inputClass} />
              </Field>
            </div>
            <p className="text-xs text-gray-500">
              선점분에서 먼저 차감하고 모자라면 사용 가능액에서 끌어옵니다. 그래도 모자라면
              <b> 실패가 아니라 부족분으로 기록</b>되며, 그 부족분은 자동으로 해소되지 않습니다.
            </p>
            <button type="button" onClick={applyOffset} disabled={!offsetFilled || busy}
              className={`${buttonClass} bg-gray-800 text-white`}>상계 걸기</button>
          </div>
        </div>
      )}
    </section>
  );
};

// ────────────────────────────────────────────────────────────────────────────
// 부족분 큐
// ────────────────────────────────────────────────────────────────────────────

const ShortfallPanel: React.FC<{ reloadToken: number }> = ({ reloadToken }) => {
  const { showToast } = useToast();
  const [rows, setRows] = useState<DepositShortfall[] | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await depositAdminApi.openShortfalls());
    } catch (err) {
      // 목록을 못 읽었을 때 빈 표를 그리면 "부족분 없음"으로 읽힌다 — 그 둘을 뭉개지 않는다.
      setRows(null);
      setError(apiErrorMessage(err, '부족분 목록을 불러오지 못했습니다.'));
    }
  }, []);

  useEffect(() => { void load(); }, [load, reloadToken]);

  const resolve = (row: DepositShortfall) => {
    if (!window.confirm(
      `부족분 #${row.id} (${fmt(row.shortfallAmount)}) 을 현재 가용 잔고에서 덮습니다.\n\n`
      + '상태만 바꾸는 것이 아니라 실제로 차감됩니다. 가용액이 모자라면 아무것도 바뀌지 않습니다.\n\n'
      + '계속하시겠습니까?')) return;
    void (async () => {
      setBusyId(row.id);
      setError(null);
      try {
        const applied = await depositAdminApi.resolveShortfall(row.id);
        showToast(`부족분 #${row.id} 해소 — ${fmt(applied)} 차감했습니다.`, 'success');
        await load();
      } catch (err) {
        setError(apiErrorMessage(err, `부족분 #${row.id} 해소에 실패했습니다.`));
      } finally {
        setBusyId(null);
      }
    })();
  };

  const writeOff = (row: DepositShortfall) => {
    if (!window.confirm(
      `부족분 #${row.id} (${fmt(row.shortfallAmount)}) 을 상각합니다.\n\n`
      + '회수를 포기한다는 판단의 기록이며 잔고는 움직이지 않습니다.\n'
      + '되돌리는 경로가 없습니다 — 상각은 종단 상태입니다.\n\n계속하시겠습니까?')) return;
    void (async () => {
      setBusyId(row.id);
      setError(null);
      try {
        await depositAdminApi.writeOffShortfall(row.id);
        showToast(`부족분 #${row.id} 을 상각했습니다.`, 'success');
        await load();
      } catch (err) {
        setError(apiErrorMessage(err, `부족분 #${row.id} 상각에 실패했습니다.`));
      } finally {
        setBusyId(null);
      }
    })();
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
      data-testid="shortfall-panel">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-gray-900">미해소 부족분</h2>
          <p className="text-sm text-gray-500 mt-1">
            상계 때 재원이 모자라 남은 금액입니다. <b>자동으로 해소되지 않습니다</b> — 재상계 배치도,
            다른 호출자도 없습니다. 여기서 덮거나 상각하지 않으면 계속 쌓입니다.
          </p>
        </div>
        <button type="button" onClick={() => void load()}
          className={`${buttonClass} shrink-0 border border-gray-300 bg-white text-gray-700`}>
          새로고침
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {rows === null ? (
        // 실패와 "0건"을 구분한다. 빈 표는 조회 실패를 "깨끗함"으로 위장시킨다.
        !error && <p className="text-sm text-gray-500">불러오는 중…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-green-700" data-testid="shortfall-empty">
          미해소 부족분이 없습니다.
        </p>
      ) : (
        <table className="w-full text-sm" data-testid="shortfall-table">
          <thead className="text-left text-gray-500">
            <tr>
              <th className="py-2">#</th><th>셀러</th><th>주체·참조</th>
              <th className="text-right">요청</th><th className="text-right">적용</th>
              <th className="text-right">부족</th><th>발생</th><th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} className="border-t" data-testid={`shortfall-row-${row.id}`}>
                <td className="py-2">{row.id}</td>
                <td>{row.sellerId}</td>
                <td className="font-mono text-xs">
                  {HOLDER_TYPES.find((t) => t.value === row.holderType)?.label ?? row.holderType}
                  {' · '}{row.holderReference}
                </td>
                {/* 요청·적용·부족을 셋 다 보여 준다 — 부족분만으로는 어느 건이 급한지 모른다. */}
                <td className="text-right">{fmt(row.requestedAmount)}</td>
                <td className="text-right">{fmt(row.appliedAmount)}</td>
                <td className="text-right font-bold text-red-700">{fmt(row.shortfallAmount)}</td>
                <td className="text-xs text-gray-500">{fmtDate(row.occurredAt)}</td>
                <td className="space-x-2 text-right whitespace-nowrap">
                  <button type="button" onClick={() => resolve(row)} disabled={busyId === row.id}
                    className={`${buttonClass} bg-blue-600 text-white`}>해소</button>
                  <button type="button" onClick={() => writeOff(row)} disabled={busyId === row.id}
                    className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>상각</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
};

const DepositAdminPage: React.FC = () => {
  // 상계는 부족분을 만들 수 있다 — 조작 후 큐를 다시 읽게 하는 신호.
  const [reloadToken, setReloadToken] = useState(0);

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-5xl mx-auto">
        <div className="mb-5">
          <h1 className="text-2xl font-bold text-gray-900">예치금 운영</h1>
          <p className="text-sm text-gray-500 mt-1">
            셀러 예치금 원장의 수기 경로입니다. 모든 조작은 원장에 기록되고, 중복은 참조 키로 막습니다.
          </p>
        </div>

        <BalancePanel onShortfallMaybeChanged={() => setReloadToken((n) => n + 1)} />
        <ShortfallPanel reloadToken={reloadToken} />
      </div>
    </div>
  );
};

export default DepositAdminPage;
