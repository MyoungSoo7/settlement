import { useState } from 'react';
import { pointApi, type ExpirePointResult, type GrantPointResult } from '@/api/point';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 포인트 운영 콘솔.
 *
 * <p>두 가지를 한다: <b>수기 지급</b>(없던 돈을 만든다)과 <b>소멸 실행</b>(고객 재산을 지운다).
 * 둘 다 되돌리기 어려운 조작이라 화면이 그 무게를 반영한다.
 *
 * <ul>
 *   <li>지급은 <b>사유가 필수</b>다. 근거 없이 포인트가 생기면 나중에 "왜 이 돈이 여기 있나"에
 *       답할 수 없고, 그 순간 원장은 설명력을 잃는다.
 *   <li>소멸은 <b>미리보기가 기본</b>이다. 실행하려면 미리보기를 먼저 돌려 규모를 본 뒤에만
 *       버튼이 열린다 — 파라미터를 빠뜨린 클릭이 실행이 되어선 안 된다.
 * </ul>
 *
 * <p>멱등 키(참조 ID)를 화면이 직접 받는 이유: 같은 보상을 두 번 눌러도 한 번만 지급되게 하려면
 * 서버가 그 키로 구분해야 한다. 자동 생성하면 재시도가 곧 이중 지급이 된다.
 */
export default function PointConsolePage() {
  const [userId, setUserId] = useState('');
  const [amount, setAmount] = useState('');
  const [referenceId, setReferenceId] = useState('');
  const [reason, setReason] = useState('');
  const [validityDays, setValidityDays] = useState('365');

  const [granting, setGranting] = useState(false);
  const [grantResult, setGrantResult] = useState<GrantPointResult | null>(null);
  const [grantError, setGrantError] = useState<string | null>(null);

  const [preview, setPreview] = useState<ExpirePointResult | null>(null);
  const [expiryResult, setExpiryResult] = useState<ExpirePointResult | null>(null);
  const [expiryBusy, setExpiryBusy] = useState(false);
  const [expiryError, setExpiryError] = useState<string | null>(null);

  const grantDisabled =
    granting || !userId.trim() || !amount.trim() || !referenceId.trim() || !reason.trim();

  const grant = async () => {
    setGrantError(null);
    setGrantResult(null);
    setGranting(true);
    try {
      const days = validityDays.trim() === '' ? null : Number(validityDays);
      setGrantResult(await pointApi.grant({
        userId: Number(userId),
        amount: Number(amount),
        referenceId: referenceId.trim(),
        reason: reason.trim(),
        validityDays: days,
      }));
    } catch (err) {
      setGrantError(apiErrorMessage(err, '포인트 지급에 실패했습니다.'));
    } finally {
      setGranting(false);
    }
  };

  const runPreview = async () => {
    setExpiryError(null);
    setExpiryResult(null);
    setExpiryBusy(true);
    try {
      setPreview(await pointApi.runExpiry(true));
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 미리보기에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  const runExpiry = async () => {
    setExpiryError(null);
    setExpiryBusy(true);
    try {
      const result = await pointApi.runExpiry(false);
      setExpiryResult(result);
      setPreview(null);   // 실행 후 미리보기는 낡은 값이 된다 — 남겨 두면 오해를 부른다.
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 실행에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  return (
    <main className="p-6 space-y-8">
      <header>
        <h1 className="text-2xl font-bold">포인트 운영</h1>
        <p className="text-sm text-gray-500">
          수기 지급과 유효기간 소멸을 실행합니다. 두 조작 모두 원장에 영구 기록됩니다.
        </p>
      </header>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">수기 지급</h2>
        <p className="text-sm text-gray-500">
          CS 보상 등으로 직접 지급합니다. 같은 참조 ID 로 다시 지급하면 한 번만 반영됩니다.
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-sm">회원 ID</span>
            <input aria-label="회원 ID" value={userId} onChange={e => setUserId(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">지급 포인트</span>
            <input aria-label="지급 포인트" value={amount} onChange={e => setAmount(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">참조 ID (멱등 키)</span>
            <input aria-label="참조 ID" value={referenceId} onChange={e => setReferenceId(e.target.value)}
              placeholder="cs-20260818-001" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">유효기간(일) — 비우면 무기한</span>
            <input aria-label="유효기간" value={validityDays} onChange={e => setValidityDays(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-sm">지급 사유 (필수)</span>
            <input aria-label="지급 사유" value={reason} onChange={e => setReason(e.target.value)}
              placeholder="배송 지연 보상" className="rounded border px-3 py-2" />
          </label>
        </div>

        <button type="button" onClick={() => void grant()} disabled={grantDisabled}
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
          {granting ? '지급 중…' : '포인트 지급'}
        </button>

        {grantError && <p role="alert" className="text-red-600">{grantError}</p>}
        {grantResult && (
          <p role="status" className="text-sm text-green-700">
            {grantResult.entryId === null
              ? '이미 지급된 참조 ID 입니다 — 중복 지급되지 않았습니다.'
              : `지급 완료: ${grantResult.grantedAmount.toLocaleString()}P (지급 후 잔액 ${grantResult.remainingBalance.toLocaleString()}P)`}
          </p>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">유효기간 소멸</h2>
        <p className="text-sm text-gray-500">
          고객 재산을 지우는 작업이라 미리보기를 먼저 실행해야 합니다. 스케줄러가 매일 자동으로
          돌지만, 장애 후 즉시 처리하거나 대상을 확인할 때 사용합니다.
        </p>

        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => void runPreview()} disabled={expiryBusy}
            className="rounded border px-4 py-2 disabled:opacity-50">
            {expiryBusy ? '실행 중…' : '미리보기'}
          </button>
          <button type="button" onClick={() => void runExpiry()} disabled={expiryBusy || preview === null}
            className="rounded bg-red-600 px-4 py-2 text-white disabled:opacity-50">
            소멸 실행
          </button>
        </div>
        {preview === null && expiryResult === null && (
          <p className="text-xs text-gray-500">미리보기를 먼저 실행하면 소멸 버튼이 열립니다.</p>
        )}

        {expiryError && <p role="alert" className="text-red-600">{expiryError}</p>}
        {preview && (
          <p role="status" className="text-sm">
            미리보기: 로트 {preview.lotCount}건 · 계정 {preview.accountCount}개 ·
            소멸 예정 {preview.forfeitedTotal.toLocaleString()}P
          </p>
        )}
        {expiryResult && (
          <p role="status" className="text-sm text-red-700">
            소멸 완료: 로트 {expiryResult.lotCount}건 ·
            소멸액 {expiryResult.forfeitedTotal.toLocaleString()}P
          </p>
        )}
      </section>
    </main>
  );
}
