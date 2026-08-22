import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SellerBankAccountForm from '@/components/SellerBankAccountForm';
import type { SellerBankAccountView } from '@/api/sellerBankAccount';

/**
 * 이 폼이 지키는 규율은 둘이고, 둘 다 <b>되돌릴 수 없는 송금</b>을 막기 위한 것이다.
 *
 * <p>① <b>마스킹된 계좌번호를 입력칸에 되채우지 않는다.</b> 서버는 조회 때 `****1234` 만 준다.
 * 그것을 채워 두면 예금주만 고치고 저장한 사용자가 계좌번호를 문자 그대로 `****1234` 로 덮게
 * 되는데, 도메인 검증이 공백만 막으므로 서버도 그 값을 받아 준다.
 *
 * <p>② <b>계좌번호가 두 칸에서 일치해야 저장할 수 있다.</b> 저장 후에는 뒤 4자리만 보이므로
 * 오타를 사후에 발견할 방법이 없고, 실재하는 남의 계좌로 나간 송금은 반송되지도 않는다.
 */

const current: SellerBankAccountView = {
  sellerId: 9, bank: 'KB', account: '****1234', holder: '홍길동', updatedAt: '2026-08-21T00:00:00Z',
};

const onSubmit = vi.fn();
beforeEach(() => vi.clearAllMocks());

const accountInput = () => screen.getByLabelText('계좌번호') as HTMLInputElement;
const confirmInput = () => screen.getByLabelText('계좌번호 확인') as HTMLInputElement;
const submitButton = () => screen.getByRole('button', { name: /계좌 (등록|정정)/ });

const fill = (account: string, confirm: string) => {
  fireEvent.change(screen.getByLabelText('은행'), { target: { value: 'KB' } });
  fireEvent.change(screen.getByLabelText('예금주'), { target: { value: '홍길동' } });
  fireEvent.change(accountInput(), { target: { value: account } });
  fireEvent.change(confirmInput(), { target: { value: confirm } });
};

describe('SellerBankAccountForm', () => {
  it('등록된 계좌가 있어도 계좌번호 칸은 비어 있다 (마스킹 값이 새 번호가 되면 안 된다)', () => {
    render(<SellerBankAccountForm current={current} saving={false} onSubmit={onSubmit} />);

    expect(accountInput().value).toBe('');
    expect(confirmInput().value).toBe('');
    // 화면 어디에도 마스킹 값이 '입력값'으로 들어가 있지 않다.
    expect(accountInput().value).not.toContain('*');
  });

  it('두 계좌번호가 다르면 저장할 수 없고 이유를 말한다', () => {
    render(<SellerBankAccountForm current={null} saving={false} onSubmit={onSubmit} />);

    fill('110123456789', '110123456700');

    expect(screen.getByRole('alert')).toHaveTextContent('계좌번호가 서로 다릅니다');
    expect(submitButton()).toBeDisabled();
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('확인란이 아직 비었을 때는 불일치라고 다그치지 않는다', () => {
    render(<SellerBankAccountForm current={null} saving={false} onSubmit={onSubmit} />);

    fill('110123456789', '');

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(submitButton()).toBeDisabled();   // 저장은 여전히 막혀 있다
  });

  it('일치하면 공백을 다듬어 넘기고 입력칸을 비운다', () => {
    render(<SellerBankAccountForm current={null} saving={false} onSubmit={onSubmit} />);

    fill('  110123456789  ', '110123456789');
    fireEvent.click(submitButton());

    expect(onSubmit).toHaveBeenCalledWith({
      bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
    });
    // 다음 셀러/다음 정정에 앞선 입력이 남아 있으면 안 된다.
    expect(accountInput().value).toBe('');
    expect(confirmInput().value).toBe('');
  });

  it('은행·예금주가 비면 저장할 수 없다', () => {
    render(<SellerBankAccountForm current={null} saving={false} onSubmit={onSubmit} />);

    fireEvent.change(accountInput(), { target: { value: '110123456789' } });
    fireEvent.change(confirmInput(), { target: { value: '110123456789' } });

    expect(submitButton()).toBeDisabled();
  });

  it('저장 중에는 입력이 모두 유효해도 다시 누를 수 없다 (중복 저장 방지)', () => {
    render(<SellerBankAccountForm current={null} saving onSubmit={onSubmit} />);

    fill('110123456789', '110123456789');

    const button = screen.getByRole('button', { name: '저장 중…' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('등록된 계좌가 있으면 버튼 문구가 "정정"이다', () => {
    render(<SellerBankAccountForm current={current} saving={false} onSubmit={onSubmit} />);
    expect(screen.getByRole('button', { name: '계좌 정정' })).toBeInTheDocument();
  });
});
