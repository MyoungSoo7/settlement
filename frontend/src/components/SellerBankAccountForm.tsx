import { useState } from 'react';
import type { SellerBankAccountInput, SellerBankAccountView } from '@/api/sellerBankAccount';

/**
 * 셀러 지급 계좌 입력 폼 — 셀러 셀프 화면과 운영자 콘솔이 <b>같은 것</b>을 쓴다.
 *
 * <p>공용으로 뽑은 이유는 재사용이 아니라 <b>두 개의 안전장치가 한 곳에만 있어야 하기</b> 때문이다.
 * 양쪽에 따로 구현하면 한쪽에서 조용히 빠진다.
 *
 * <p><b>① 계좌번호를 되채우지 않는다.</b> 서버는 조회 때 마스킹({@code ****1234})만 준다. 그 값을
 * 폼에 채워 두면 사용자가 예금주만 고치고 저장했을 때 계좌번호가 문자 그대로 `****1234` 로 덮인다 —
 * 도메인 검증이 공백만 막으므로 서버도 받아 준다. 정정하려면 매번 전체를 다시 입력한다.
 *
 * <p><b>② 계좌번호를 두 번 받는다.</b> 저장 후에는 마스킹된 뒤 4자리밖에 볼 수 없어서 오타를
 * 사후에 발견할 방법이 없다. 게다가 <b>실재하지만 남의 계좌</b>로 보낸 송금은 반송되지 않는다 —
 * 반송(bounce)은 계좌가 없을 때만 돌아온다. 즉 오타 한 번이 회수 불가능한 송금이 된다.
 */

/** 서버가 닫힌 목록으로 강제하지 않으므로(공백만 검증) 제안일 뿐이다 — 여기 없는 은행도 입력 가능. */
const BANK_SUGGESTIONS = ['KB', 'SHINHAN', 'WOORI', 'HANA', 'NH', 'IBK', 'TOSS', 'KAKAO'];

interface Props {
  /** 현재 등록된 계좌(마스킹). 표시에만 쓰고 입력값으로 <b>절대</b> 흘려보내지 않는다. */
  current: SellerBankAccountView | null;
  saving: boolean;
  onSubmit: (input: SellerBankAccountInput) => void;
  /** 폼 안의 라벨을 화면마다 구분한다 — 한 페이지에 폼이 둘일 때 접근성 조회가 모호해진다. */
  idPrefix?: string;
}

export default function SellerBankAccountForm({ current, saving, onSubmit, idPrefix = 'sba' }: Props) {
  const [bankCode, setBankCode] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [confirmNumber, setConfirmNumber] = useState('');
  const [accountHolder, setAccountHolder] = useState('');

  const trimmed = {
    bankCode: bankCode.trim(),
    accountNumber: accountNumber.trim(),
    accountHolder: accountHolder.trim(),
  };
  const filled = trimmed.bankCode !== '' && trimmed.accountNumber !== '' && trimmed.accountHolder !== '';
  // 확인란은 비어 있을 때 "불일치"라고 다그치지 않는다 — 아직 입력 중인 것과 틀린 것은 다르다.
  const mismatch = confirmNumber.trim() !== '' && confirmNumber.trim() !== trimmed.accountNumber;
  const matched = confirmNumber.trim() === trimmed.accountNumber;

  const submit = () => {
    if (!filled || !matched || saving) return;
    onSubmit(trimmed);
    setBankCode('');
    setAccountNumber('');
    setConfirmNumber('');
    setAccountHolder('');
  };

  const field = (name: string) => `${idPrefix}-${name}`;

  return (
    <div className="space-y-3">
      <datalist id={field('banks')}>
        {BANK_SUGGESTIONS.map(code => <option key={code} value={code} />)}
      </datalist>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block text-sm" htmlFor={field('bank')}>
          <span className="text-gray-600">은행</span>
          <input id={field('bank')} list={field('banks')} value={bankCode}
            onChange={e => setBankCode(e.target.value)}
            placeholder="KB"
            className="mt-1 w-full rounded border px-3 py-2 font-mono" />
        </label>

        <label className="block text-sm" htmlFor={field('holder')}>
          <span className="text-gray-600">예금주</span>
          <input id={field('holder')} value={accountHolder}
            onChange={e => setAccountHolder(e.target.value)}
            className="mt-1 w-full rounded border px-3 py-2" />
        </label>

        <label className="block text-sm" htmlFor={field('account')}>
          <span className="text-gray-600">계좌번호</span>
          <input id={field('account')} value={accountNumber} inputMode="numeric"
            onChange={e => setAccountNumber(e.target.value)}
            autoComplete="off"
            className="mt-1 w-full rounded border px-3 py-2 font-mono" />
        </label>

        <label className="block text-sm" htmlFor={field('confirm')}>
          <span className="text-gray-600">계좌번호 확인</span>
          {/* 붙여넣기를 막지 않는다. 막아도 첫 칸을 복사해 오는 것은 못 막으면서, 손 사용이
              불편한 사용자와 비밀번호 관리자만 확실히 불편해진다. 오타의 최종 방어선은 저장 후
              화면에 뜨는 뒤 4자리를 사용자가 자기 은행 앱과 대조하는 것이다. */}
          <input id={field('confirm')} value={confirmNumber} inputMode="numeric"
            onChange={e => setConfirmNumber(e.target.value)}
            autoComplete="off"
            className="mt-1 w-full rounded border px-3 py-2 font-mono" />
        </label>
      </div>

      {mismatch && (
        <p role="alert" className="text-sm text-red-600">
          계좌번호가 서로 다릅니다. 저장 후에는 뒤 4자리만 보이므로 지금 확인해야 합니다.
        </p>
      )}

      <div className="flex items-center gap-3">
        <button type="button" onClick={submit} disabled={!filled || !matched || saving}
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
          {saving ? '저장 중…' : current ? '계좌 정정' : '계좌 등록'}
        </button>
        {current && (
          <span className="text-xs text-gray-500">
            정정하면 이후 지급부터 새 계좌로 나갑니다. 이미 나간 송금은 바뀌지 않습니다.
          </span>
        )}
      </div>
    </div>
  );
}
