import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PayoutAdminPage from '@/pages/PayoutAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { payoutApi, type Payout } from '@/api/payout';

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

    fireEvent.click(screen.getByRole('button', { name: '지급 대기' }));

    await waitFor(() => expect(payoutApi.listPending).toHaveBeenCalledWith(50));
    await waitFor(() => expect(screen.getByText('지급 대기 건이 없습니다.')).toBeInTheDocument());
  });
});
