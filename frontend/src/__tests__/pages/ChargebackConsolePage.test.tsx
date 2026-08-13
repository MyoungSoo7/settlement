import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import ChargebackConsolePage from '@/pages/settlement/ChargebackConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { chargebackApi } from '@/api/chargeback';

vi.mock('@/api/chargeback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/chargeback')>();
  return {
    ...actual,
    chargebackApi: { list: vi.fn(), get: vi.fn(), open: vi.fn(), accept: vi.fn(), reject: vi.fn() },
  };
});

const mocked = vi.mocked(chargebackApi);

const openCb = {
  id: 3, paymentId: 501, settlementId: 77, amount: '25000',
  reasonCode: 'FRAUD' as const, reasonDetail: '카드사 도용 통지',
  status: 'OPEN' as const, source: 'MANUAL' as const, pgChargebackId: null,
  decidedBy: null, decisionNote: null, raisedAt: '2026-08-12T09:00:00', decidedAt: null,
};

const renderPage = () => render(<ToastProvider><ChargebackConsolePage /></ToastProvider>);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.list.mockResolvedValue([openCb]);
});

afterEach(() => vi.restoreAllMocks());

describe('ChargebackConsolePage — 목록', () => {
  it('진입하면 미결(OPEN) 분쟁을 먼저 보여 준다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith('OPEN'));
    expect(screen.getByText('도용·사기')).toBeInTheDocument();
  });

  it('상태 탭을 바꾸면 그 상태로 다시 조회한다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith('OPEN'));

    fireEvent.click(screen.getByRole('button', { name: '수락(셀러 부담)' }));

    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith('ACCEPTED'));
  });

  it('결정이 끝난 분쟁에는 수락·기각 버튼이 없다', async () => {
    mocked.list.mockResolvedValue([{
      ...openCb, status: 'ACCEPTED', decidedBy: 'admin', decidedAt: '2026-08-12T10:00:00',
    }]);
    renderPage();

    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: '수락' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '기각' })).not.toBeInTheDocument();
  });
});

describe('ChargebackConsolePage — 결정 안전장치', () => {
  it('수락 확인창은 차감 금액과 되돌릴 수 없음을 명시한다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '수락' }));

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('25,000원 이 차감됩니다'));
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('되돌릴 수 없습니다'));
    expect(mocked.accept).not.toHaveBeenCalled();
  });

  it('기각 확인창은 정산 영향이 없음을 명시한다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '기각' }));

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('정산에는 영향이 없습니다'));
  });

  it('기각 사유가 비면 API 를 부르지 않는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('  ');
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '기각' }));

    await waitFor(() => expect(mocked.reject).not.toHaveBeenCalled());
  });

  it('수락하면 사유와 함께 호출하고 목록을 갱신한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('카드사 확정');
    mocked.accept.mockResolvedValue({ ...openCb, status: 'ACCEPTED' });
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: '수락' }));

    await waitFor(() => expect(mocked.accept).toHaveBeenCalledWith(3, '카드사 확정'));
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });

  it('409(이미 처리됨)는 실패가 아니라 안내로 다루고 목록을 갱신한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('');
    mocked.accept.mockRejectedValue({ isAxiosError: true, response: { status: 409 } });
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: '수락' }));

    await waitFor(() => expect(screen.getByText(/이미 처리된 결정입니다/)).toBeInTheDocument());
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });
});

describe('ChargebackConsolePage — 수동 등록', () => {
  it('ID 가 비면 등록하지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '수동 등록' }));
    fireEvent.click(screen.getByRole('button', { name: '분쟁 등록' }));

    await waitFor(() => expect(mocked.open).not.toHaveBeenCalled());
  });

  it('금액이 0 이하면 등록하지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '수동 등록' }));

    fireEvent.change(screen.getByLabelText('결제 ID'), { target: { value: '501' } });
    fireEvent.change(screen.getByLabelText('정산 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('금액'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: '분쟁 등록' }));

    await waitFor(() => expect(mocked.open).not.toHaveBeenCalled());
  });

  it('정상 입력이면 등록하고 미결 목록으로 돌아온다', async () => {
    mocked.open.mockResolvedValue({ ...openCb, id: 9 });
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '수동 등록' }));

    fireEvent.change(screen.getByLabelText('결제 ID'), { target: { value: '501' } });
    fireEvent.change(screen.getByLabelText('정산 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('금액'), { target: { value: '25000' } });
    fireEvent.click(screen.getByRole('button', { name: '분쟁 등록' }));

    await waitFor(() => expect(mocked.open).toHaveBeenCalledWith({
      paymentId: 501, settlementId: 77, amount: '25000',
      reasonCode: 'FRAUD', reasonDetail: undefined,
    }));
  });
});
