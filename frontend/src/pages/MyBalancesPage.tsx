import { useCallback, useEffect, useState } from 'react';
import { pointApi } from '@/api/point';
import { giftCardApi, type RegisterGiftCardResult } from '@/api/giftCard';
import { depositApi, type DepositAccount } from '@/api/deposit';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 내 포인트·상품권·예치금.
 *
 * <p>잔액을 <b>합쳐 보여 주지 않는다.</b> 셋은 회계에서도 다른 계정이고 유효기간과 사용 규칙도
 * 다르다. 한 숫자로 합치면 "왜 이만큼밖에 못 쓰지"라는 질문에 답할 수 없다.
 *
 * <p>예치금은 셀러에게만 계좌가 있다. 계좌가 없으면(서버 404) 카드를 <b>그리지 않는다</b> —
 * 0원으로 그리면 셀러가 아닌 사용자에게 "내 예치금은 0원"이라는 틀린 사실을 보여 주게 되고,
 * 셀러에게는 "계좌가 안 열렸다"와 "잔고를 다 썼다"가 같은 화면이 된다. 서버가 404 로 나눠 준
 * 구분을 화면에서 지우지 않는다.
 *
 * <p>예치금은 available·locked 를 함께 보여 준다. 합계만 보이면 카드 승인으로 선점된 금액 때문에
 * 결제가 막혔을 때 그 이유가 화면에 없다.
 *
 * <p><b>등록 실패 문구를 화면이 지어내지 않는다.</b> 서버가 실패 사유를 구분하지 않는 이유는
 * 유효한 코드의 존재를 흘리지 않기 위함인데, 화면이 "이미 등록된 코드입니다" 같은 추측을 보여
 * 주면 그 방어가 무너진다. 서버 문구를 그대로 전달한다.
 */
export default function MyBalancesPage() {
  const [pointBalance, setPointBalance] = useState<number | null>(null);
  const [giftCardBalance, setGiftCardBalance] = useState<number | null>(null);
  const [deposit, setDeposit] = useState<DepositAccount | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [code, setCode] = useState('');
  const [redeeming, setRedeeming] = useState(false);
  const [redeemed, setRedeemed] = useState<RegisterGiftCardResult | null>(null);
  const [redeemError, setRedeemError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [point, giftCard, depositAccount] = await Promise.all([
        pointApi.myBalance(),
        giftCardApi.myBalance(),
        depositApi.myAccount(),
      ]);
      setPointBalance(point.available);
      setGiftCardBalance(giftCard.available);
      setDeposit(depositAccount);
    } catch (err) {
      setLoadError(apiErrorMessage(err, '잔액을 불러오지 못했습니다.'));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const redeem = async () => {
    setRedeemError(null);
    setRedeemed(null);
    setRedeeming(true);
    try {
      const result = await giftCardApi.redeem(code.trim());
      setRedeemed(result);
      setCode('');
      await load();
    } catch (err) {
      // 서버 문구를 그대로 쓴다 — 사유를 추측해 보여 주면 코드 존재 여부가 새어 나간다.
      setRedeemError(apiErrorMessage(err, '기프트카드를 등록하지 못했습니다.'));
    } finally {
      setRedeeming(false);
    }
  };

  return (
    <main className="mx-auto max-w-2xl p-6 space-y-8">
      <header>
        <h1 className="text-2xl font-bold">내 잔액</h1>
        <p className="text-sm text-gray-500">
          결제 시 사용할 수 있는 잔액입니다. 종류마다 유효기간과 사용 규칙이 달라 따로 표시합니다.
        </p>
      </header>

      {loadError && <p role="alert" className="text-red-600">{loadError}</p>}

      <section className="grid gap-4 sm:grid-cols-2">
        <div className="rounded border p-4">
          <h2 className="text-sm text-gray-500">포인트</h2>
          <p className="text-2xl font-bold" data-testid="point-balance">
            {pointBalance === null ? '—' : `${pointBalance.toLocaleString()}P`}
          </p>
        </div>
        <div className="rounded border p-4">
          <h2 className="text-sm text-gray-500">기프트카드</h2>
          <p className="text-2xl font-bold" data-testid="giftcard-balance">
            {giftCardBalance === null ? '—' : `${giftCardBalance.toLocaleString()}원`}
          </p>
        </div>
      </section>

      {/* 계좌가 없는 사용자(= 셀러가 아님)에게는 이 구획 자체가 없다 — 0원으로 그리면 틀린 사실이 된다. */}
      {deposit && (
        <section className="rounded border p-4 space-y-2" data-testid="deposit-section">
          <div className="flex items-baseline justify-between">
            <h2 className="text-sm text-gray-500">예치금 (셀러)</h2>
            <p className="text-2xl font-bold" data-testid="deposit-available">
              {deposit.available.toLocaleString()}원
            </p>
          </div>
          <dl className="flex gap-6 text-sm text-gray-600">
            <div className="flex gap-1">
              <dt>묶인 금액</dt>
              <dd data-testid="deposit-locked">{deposit.locked.toLocaleString()}원</dd>
            </div>
            <div className="flex gap-1">
              <dt>합계</dt>
              <dd data-testid="deposit-total">{deposit.total.toLocaleString()}원</dd>
            </div>
          </dl>
          <p className="text-xs text-gray-500">
            묶인 금액은 카드 승인 등으로 이미 선점되어 지금은 쓸 수 없는 금액입니다. 합계는 사용
            가능액과 묶인 금액을 더한 값입니다.
          </p>
        </section>
      )}

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">기프트카드 등록</h2>
        <p className="text-sm text-gray-500">
          받은 코드를 입력하면 잔액에 더해집니다. 하이픈이나 대소문자는 신경 쓰지 않아도 됩니다.
        </p>

        <div className="flex flex-wrap gap-2">
          <input aria-label="기프트카드 코드" value={code} onChange={e => setCode(e.target.value)}
            placeholder="GC-XXXX-XXXX-XXXX-XXXX"
            className="flex-1 rounded border px-3 py-2 font-mono" />
          <button type="button" onClick={() => void redeem()}
            disabled={redeeming || code.trim() === ''}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
            {redeeming ? '등록 중…' : '등록'}
          </button>
        </div>

        {redeemError && <p role="alert" className="text-red-600">{redeemError}</p>}
        {redeemed && (
          <p role="status" className="text-sm text-green-700">
            {redeemed.faceAmount.toLocaleString()}원 상품권(****{redeemed.codeLast4})을 등록했습니다.
          </p>
        )}
      </section>
    </main>
  );
}
