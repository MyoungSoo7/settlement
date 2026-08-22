import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PayoutAdminPage from '@/pages/PayoutAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { payoutApi, type Payout } from '@/api/payout';
import { settlementApi } from '@/api/settlement';
import { sellerBankAccountApi } from '@/api/sellerBankAccount';

vi.mock('@/api/settlement', () => ({ settlementApi: { holdbackPreview: vi.fn() } }));
vi.mock('@/api/sellerBankAccount', () => ({
  sellerBankAccountApi: { mine: vi.fn(), saveMine: vi.fn(), of: vi.fn(), save: vi.fn() },
}));

vi.mock('@/api/payout', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/payout')>();
  return {
    ...actual,
    payoutApi: {
      listFailed: vi.fn(),
      listPending: vi.fn(),
      get: vi.fn(),
      retry: vi.fn(),
      cancel: vi.fn(),
      bounce: vi.fn(),
      preview: vi.fn(),
      executeNow: vi.fn(),
    },
  };
});

const failedPayout: Payout = {
  id: 11,
  settlementId: 500,
  sellerId: 7,
  amount: 1_250_000,
  status: 'FAILED',
  bank: '004',
  account: '1234****89',
  holder: '홍길동',
  firmBankingTxnId: null,
  failureReason: '예금주 불일치',
  retryCount: 1,
  operatorId: 'admin@example.com',
  requestedAt: '2026-08-09T09:00:00',
  sentAt: null,
  completedAt: null,
  failedAt: '2026-08-09T09:05:00',
};

const renderPage = () =>
  render(
    <ToastProvider>
      <PayoutAdminPage />
    </ToastProvider>
  );

describe('PayoutAdminPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(payoutApi.listFailed).mockResolvedValue([failedPayout]);
    vi.mocked(payoutApi.listPending).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('실패 목록과 실패 사유·마스킹 계좌를 보여준다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText(/지급 #11/)).toBeInTheDocument());
    expect(screen.getByText(/실패 사유: 예금주 불일치/)).toBeInTheDocument();
    // 계좌번호 원문은 서버가 주지 않는다 — 화면도 마스킹만 노출한다
    expect(screen.getByText(/1234\*\*\*\*89/)).toBeInTheDocument();
  });

  it('재시도는 멱등 키와 함께 호출된다', async () => {
    vi.mocked(payoutApi.retry).mockResolvedValue({ ...failedPayout, status: 'REQUESTED' });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재시도' }));

    await waitFor(() => expect(payoutApi.retry).toHaveBeenCalledTimes(1));
    const [id, key] = vi.mocked(payoutApi.retry).mock.calls[0];
    expect(id).toBe(11);
    expect(key).toBeTruthy();
  });

  /** 사유 없는 영구 취소는 감사 기록에 아무 근거도 남기지 않는다. */
  it('취소는 사유가 없으면 확정 버튼이 비활성이다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '영구 취소' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '영구 취소' }));

    expect(screen.getByRole('button', { name: '취소 확정' })).toBeDisabled();
  });

  it('사유를 적으면 취소가 사유·멱등 키와 함께 호출된다', async () => {
    vi.mocked(payoutApi.cancel).mockResolvedValue({ ...failedPayout, status: 'CANCELED' });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '영구 취소' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '영구 취소' }));
    fireEvent.change(screen.getByLabelText('취소 사유'), { target: { value: '계좌 폐쇄' } });
    fireEvent.click(screen.getByRole('button', { name: '취소 확정' }));

    await waitFor(() => expect(payoutApi.cancel).toHaveBeenCalledTimes(1));
    const [id, reason, key] = vi.mocked(payoutApi.cancel).mock.calls[0];
    expect([id, reason]).toEqual([11, '계좌 폐쇄']);
    expect(key).toBeTruthy();
  });

  /** 서버의 멱등 방어가 동작한 결과라 오류가 아니다 — 사용자에게 그렇게 알려야 한다. */
  it('409 는 오류가 아니라 중복 안내로 표시한다', async () => {
    vi.mocked(payoutApi.retry).mockRejectedValue({ response: { status: 409, data: {} } });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재시도' }));

    await waitFor(() =>
      expect(screen.getByText('이미 처리된 요청입니다 (중복 방지).')).toBeInTheDocument()
    );
  });

  describe('송금 실행', () => {
    beforeEach(() => {
      vi.mocked(payoutApi.preview).mockResolvedValue({
        sendableCount: 3,
        sendableAmount: 3_000_000,
        limitedCount: 1,
        limitedAmount: 500_000,
        lines: [
          { payoutId: 21, sellerId: 7, amount: 1_000_000, sendable: true, reason: null },
          { payoutId: 22, sellerId: 8, amount: 500_000, sendable: false, reason: '계좌 미등록' },
        ],
      });
    });

    it('미리보기는 송금 가능·보류 규모와 보류 사유를 보여준다', async () => {
      renderPage();
      fireEvent.click(screen.getByRole('button', { name: '미리보기' }));

      await waitFor(() => expect(screen.getByText('3건')).toBeInTheDocument());
      expect(screen.getByText('1건')).toBeInTheDocument();
      expect(screen.getByText('계좌 미등록')).toBeInTheDocument();
    });

    /** 되돌리기 어려운 외부 송금이다. 확인 없이 실행되면 안 된다. */
    it('확인 창에서 취소하면 송금이 실행되지 않는다', async () => {
      vi.stubGlobal('confirm', vi.fn(() => false));
      renderPage();
      fireEvent.click(screen.getByRole('button', { name: '미리보기' }));
      await waitFor(() => expect(screen.getByRole('button', { name: '지금 송금 실행' })).toBeInTheDocument());

      fireEvent.click(screen.getByRole('button', { name: '지금 송금 실행' }));

      expect(payoutApi.executeNow).not.toHaveBeenCalled();
    });

    it('확인하면 실행하고 집행 결과를 알려준다', async () => {
      vi.stubGlobal('confirm', vi.fn(() => true));
      vi.mocked(payoutApi.executeNow).mockResolvedValue({ succeeded: 3, failed: 0, limitedSkipped: 1 });
      renderPage();
      fireEvent.click(screen.getByRole('button', { name: '미리보기' }));
      await waitFor(() => expect(screen.getByRole('button', { name: '지금 송금 실행' })).toBeInTheDocument());

      fireEvent.click(screen.getByRole('button', { name: '지금 송금 실행' }));

      await waitFor(() => expect(payoutApi.executeNow).toHaveBeenCalledTimes(1));
      await waitFor(() =>
        expect(screen.getByText(/송금 완료 3건 · 실패 0건 · 한도로 보류 1건/)).toBeInTheDocument()
      );
    });
  });

  it('지급 대기 탭으로 바꾸면 대기 목록을 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(payoutApi.listFailed).toHaveBeenCalled());

    fireEvent.click(await screen.findByRole('button', { name: '지급 대기' }));

    await waitFor(() => expect(payoutApi.listPending).toHaveBeenCalledWith(50));
    await waitFor(() => expect(screen.getByText('지급 대기 건이 없습니다.')).toBeInTheDocument());
  });

  /**
   * 홀드백 미리보기는 조회 전용이다 — 이 패널에 실행 버튼을 두면 "미리보기 화면에서
   * 눌렀을 뿐인데 지급이 나갔다"가 가능해진다. 그리고 잘린 결과를 잘렸다고 말하지 않으면
   * 운영자가 목록 길이를 전체 규모로 읽고 자금 계획을 세운다.
   */
  describe('홀드백 해제 미리보기', () => {
    it('조회 결과를 건수·금액으로 보여 주되 해제 버튼은 두지 않는다', async () => {
      vi.mocked(settlementApi.holdbackPreview).mockResolvedValue({
        count: 2, totalAmount: 300000, truncated: false,
        lines: [
          { settlementId: 501, paymentId: 900, holdbackAmount: 100000, releaseDate: '2026-09-01' },
          { settlementId: 502, paymentId: 901, holdbackAmount: 200000, releaseDate: '2026-09-01' },
        ],
      });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: '조회' }));

      await waitFor(() => expect(screen.getByText('2건')).toBeInTheDocument());
      expect(screen.getByText(/이 화면에서는 아무것도 해제되지 않습니다/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /해제/ })).not.toBeInTheDocument();
    });

    it('한도까지 가득 차면 전체가 아니라고 알린다', async () => {
      vi.mocked(settlementApi.holdbackPreview).mockResolvedValue({
        count: 200, totalAmount: 50000000, truncated: true,
        lines: [{ settlementId: 501, paymentId: 900, holdbackAmount: 100000, releaseDate: '2026-09-01' }],
      });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: '조회' }));

      await waitFor(() => expect(screen.getByText(/전체가 아닙니다/)).toBeInTheDocument());
    });

    it('풀릴 게 없으면 없다고 말한다 — 빈 표로 두면 조회 실패와 구분되지 않는다', async () => {
      vi.mocked(settlementApi.holdbackPreview).mockResolvedValue({
        count: 0, totalAmount: 0, truncated: false, lines: [],
      });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: '조회' }));

      await waitFor(() =>
        expect(screen.getByText('이 날짜에 풀릴 홀드백이 없습니다.')).toBeInTheDocument());
    });
  });

  /**
   * 계좌 미등록은 <b>실패로도 안 보인다</b> — payout 이 아예 만들어지지 않아 실패·대기 목록
   * 어디에도 없다. 그래서 셀러 번호로 직접 조회하는 입구가 이 콘솔에 있다.
   */
  describe('셀러 지급 계좌', () => {
    const account = {
      sellerId: 7, bank: 'KB', account: '****1234', holder: '홍길동',
      updatedAt: '2026-08-21T00:00:00Z',
    };

    const lookupSeller = async (id: string) => {
      fireEvent.change(screen.getByLabelText('셀러 번호'), { target: { value: id } });
      // 홀드백 패널에도 '조회' 버튼이 있다 — 이름이 겹치면 스크린리더에서 구분되지 않으므로
      // 화면 쪽을 '계좌 조회'로 구분했다. 이 조회가 그 사실을 붙잡아 준다.
      fireEvent.click(screen.getByRole('button', { name: '계좌 조회' }));
    };

    it('미등록 셀러는 "지금 지급되지 않고 있다"고 말한다', async () => {
      vi.mocked(sellerBankAccountApi.of).mockResolvedValue(null);
      renderPage();

      await waitFor(() => expect(screen.getByTestId('seller-bank-account-panel')).toBeInTheDocument());
      await lookupSeller('7');

      await waitFor(() => expect(screen.getByTestId('sba-missing')).toBeInTheDocument());
      expect(screen.getByTestId('sba-missing')).toHaveTextContent('지금 지급되지 않고 있습니다');
    });

    it('조회 후 셀러 번호를 바꾸면 앞선 결과를 버린다 — 엉뚱한 셀러 계좌를 덮어쓰지 않는다', async () => {
      vi.mocked(sellerBankAccountApi.of).mockResolvedValue(account);
      renderPage();

      await waitFor(() => expect(screen.getByTestId('seller-bank-account-panel')).toBeInTheDocument());
      await lookupSeller('7');
      await waitFor(() => expect(screen.getByTestId('sba-current')).toBeInTheDocument());

      // 조회는 7 로 해 놓고 입력칸만 9 로 바꾼 상황. 화면에 7 의 계좌가 남아 있으면
      // 조작자는 7 을 고치는 줄 알고 저장하는데 실제로는 9 에 쓰인다.
      fireEvent.change(screen.getByLabelText('셀러 번호'), { target: { value: '9' } });

      expect(screen.queryByTestId('sba-result')).not.toBeInTheDocument();
      expect(screen.queryByTestId('sba-current')).not.toBeInTheDocument();
    });

    it('저장은 조회한 셀러를 대상으로 한다', async () => {
      vi.mocked(sellerBankAccountApi.of).mockResolvedValue(null);
      vi.mocked(sellerBankAccountApi.save).mockResolvedValue(account);
      renderPage();

      await waitFor(() => expect(screen.getByTestId('seller-bank-account-panel')).toBeInTheDocument());
      await lookupSeller('7');
      await waitFor(() => expect(screen.getByTestId('sba-result')).toBeInTheDocument());

      fireEvent.change(screen.getByLabelText('은행'), { target: { value: 'KB' } });
      fireEvent.change(screen.getByLabelText('예금주'), { target: { value: '홍길동' } });
      fireEvent.change(screen.getByLabelText('계좌번호'), { target: { value: '110123456789' } });
      fireEvent.change(screen.getByLabelText('계좌번호 확인'), { target: { value: '110123456789' } });
      fireEvent.click(screen.getByRole('button', { name: '계좌 등록' }));

      await waitFor(() => expect(sellerBankAccountApi.save).toHaveBeenCalledWith(7, {
        bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
      }));
    });

    it('조회 전에는 입력 폼이 없다 — 대상 없이 계좌를 쓸 수 있으면 안 된다', async () => {
      renderPage();

      await waitFor(() => expect(screen.getByTestId('seller-bank-account-panel')).toBeInTheDocument());

      expect(screen.queryByTestId('sba-result')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('계좌번호')).not.toBeInTheDocument();
    });
  });
});
