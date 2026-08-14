import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import CeoCardPage from '@/pages/CeoCardPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { cardApi } from '@/api/card';

/**
 * 법인카드 콘솔이 지켜야 하는 것.
 *   ① 한도에는 산정 근거(평판등급·재원·인정비율)가 붙는다 — 답 없는 여신 화면은 CS 비용이다.
 *   ② 정지·재개·해지는 사유 없이 나가지 않는다 — 서버가 400 으로 끊기 전에 화면이 먼저 막는다.
 *   ③ 해지는 비가역(한도 반환)이라 확인을 받는다.
 *   ④ 정지 카드도 한도를 점유한다 — Σ서브한도가 해지만 제외하고 집계됨을 화면이 그대로 보여 준다.
 */
vi.mock('@/api/card', () => ({
  cardApi: {
    openAccount: vi.fn(),
    getAccount: vi.fn(),
    listCards: vi.fn(),
    issueCard: vi.fn(),
    changeSubLimit: vi.fn(),
    changeStatus: vi.fn(),
    myCards: vi.fn(),
  },
}));

const mocked = vi.mocked(cardApi);

const account = (over: Partial<Awaited<ReturnType<typeof cardApi.getAccount>>> = {}) => ({
  id: 3, organizationId: 7, sellerId: 'seller-7', status: 'ACTIVE' as const,
  masterLimit: 7000000, reputationGrade: 'B',
  sellerPayable: 8000000, holdbackPayable: 2000000, appliedRatio: 0.7,
  rejectReason: null, ...over,
});

const card = (over: Partial<Awaited<ReturnType<typeof cardApi.myCards>>[number]> = {}) => ({
  id: 11, cardAccountId: 3, holderUserId: 42, maskedCardNo: '9410-****-****-1234',
  subLimit: 500000, status: 'ISSUED' as const, ...over,
});

const renderPage = () => render(<ToastProvider><CeoCardPage /></ToastProvider>);

const lookupAccount = async () => {
  fireEvent.change(screen.getByLabelText('카드계정 ID'), { target: { value: '3' } });
  fireEvent.click(screen.getByRole('button', { name: '계정 조회' }));
  await waitFor(() => expect(mocked.getAccount).toHaveBeenCalledWith(3));
};

let confirmSpy: ReturnType<typeof vi.spyOn>;
let promptSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.myCards.mockResolvedValue([card()]);
  mocked.getAccount.mockResolvedValue(account());
  mocked.listCards.mockResolvedValue([card()]);
  mocked.openAccount.mockResolvedValue(account({ status: 'SCREENING', masterLimit: 0 }));
  mocked.issueCard.mockResolvedValue(card({ id: 12 }));
  mocked.changeSubLimit.mockResolvedValue(card({ subLimit: 300000 }));
  mocked.changeStatus.mockResolvedValue(card({ status: 'SUSPENDED' }));
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('휴직 처리');
});

afterEach(() => {
  confirmSpy.mockRestore();
  promptSpy.mockRestore();
});

describe('CeoCardPage — 진입', () => {
  it('진입 시 내 카드만 읽는다 — 계정은 ID 를 알아야 조회하는 화면이다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.myCards).toHaveBeenCalled());
    expect(await screen.findByText('9410-****-****-1234')).toBeInTheDocument();
    expect(mocked.getAccount).not.toHaveBeenCalled();
  });

  it('내 카드가 없으면 빈 상태를 명시한다', async () => {
    mocked.myCards.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/발급받은 카드가 없습니다/)).toBeInTheDocument());
  });
});

describe('CeoCardPage — 카드계정 조회', () => {
  it('계정과 카드 목록을 함께 읽고, 한도의 산정 근거를 보여 준다', async () => {
    renderPage();
    await lookupAccount();

    await waitFor(() => expect(mocked.listCards).toHaveBeenCalledWith(3));
    expect(await screen.findByText('7,000,000')).toBeInTheDocument(); // masterLimit
    expect(screen.getByText(/평판등급/)).toBeInTheDocument();
    expect(screen.getByText('8,000,000')).toBeInTheDocument();      // sellerPayable
    expect(screen.getByText(/70%/)).toBeInTheDocument();            // appliedRatio
  });

  it('Σ서브한도는 해지만 제외하고 집계된다 — 정지 카드도 한도를 점유한다', async () => {
    mocked.listCards.mockResolvedValue([
      card({ id: 11, subLimit: 500000, status: 'ISSUED' }),
      card({ id: 12, subLimit: 300000, status: 'SUSPENDED' }),
      card({ id: 13, subLimit: 900000, status: 'CANCELED' }),
    ]);
    renderPage();
    await lookupAccount();

    // 500,000 + 300,000 (정지 포함) — 해지 900,000 은 제외
    await waitFor(() => expect(screen.getByText(/800,000/)).toBeInTheDocument());
  });

  it('계정 ID 없이 조회하면 서버를 부르지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.myCards).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole('button', { name: '계정 조회' }));

    await waitFor(() => expect(mocked.getAccount).not.toHaveBeenCalled());
  });

  it('조회 실패는 화면에 남긴다', async () => {
    mocked.getAccount.mockRejectedValue({ response: { data: { message: '해당 조직의 구성원이 아닙니다' } } });
    renderPage();
    fireEvent.change(screen.getByLabelText('카드계정 ID'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: '계정 조회' }));

    await waitFor(() => expect(screen.getByText(/구성원이 아닙니다/)).toBeInTheDocument());
  });

  it('심사 탈락 계정은 사유를 보여 준다', async () => {
    mocked.getAccount.mockResolvedValue(account({ status: 'REJECTED', rejectReason: '평판 E등급 — 재원 인정 불가' }));
    renderPage();
    await lookupAccount();

    await waitFor(() => expect(screen.getByText(/평판 E등급/)).toBeInTheDocument());
  });
});

describe('CeoCardPage — 계정 개설', () => {
  it('조직 ID 로 개설을 확인받고 요청한다 — 요청자는 본문에 싣지 않는다', async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('조직 ID'), { target: { value: '7' } });
    fireEvent.click(screen.getByRole('button', { name: '계정 개설' }));

    await waitFor(() => expect(mocked.openAccount).toHaveBeenCalledWith(7));
  });

  it('확인을 거부하면 개설하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    fireEvent.change(screen.getByLabelText('조직 ID'), { target: { value: '7' } });
    fireEvent.click(screen.getByRole('button', { name: '계정 개설' }));

    await waitFor(() => expect(mocked.openAccount).not.toHaveBeenCalled());
  });
});

describe('CeoCardPage — 발급·한도·상태', () => {
  it('발급은 대상과 서브한도를 보내고, 목록을 다시 읽는다', async () => {
    renderPage();
    await lookupAccount();
    await waitFor(() => expect(mocked.listCards).toHaveBeenCalledTimes(1));

    fireEvent.change(await screen.findByLabelText('임직원 사용자 ID'), { target: { value: '42' } });
    fireEvent.change(screen.getByLabelText('서브한도(원)'), { target: { value: '500000' } });
    fireEvent.click(screen.getByRole('button', { name: '카드 발급' }));

    await waitFor(() => expect(mocked.issueCard).toHaveBeenCalledWith(3, { holderUserId: 42, subLimit: 500000 }));
    await waitFor(() => expect(mocked.listCards).toHaveBeenCalledTimes(2));
  });

  it('한도 변경은 새 값을 입력받아 보낸다', async () => {
    promptSpy.mockReturnValue('300000');
    renderPage();
    await lookupAccount();
    fireEvent.click(await screen.findByRole('button', { name: '한도 변경' }));

    await waitFor(() => expect(mocked.changeSubLimit).toHaveBeenCalledWith(11, 300000));
  });

  it('정지는 사유를 입력받아 보낸다 — 사유를 취소하면 나가지 않는다', async () => {
    renderPage();
    await lookupAccount();
    fireEvent.click(await screen.findByRole('button', { name: '정지' }));
    await waitFor(() => expect(mocked.changeStatus).toHaveBeenCalledWith(11, {
      status: 'SUSPENDED', reason: '휴직 처리',
    }));

    promptSpy.mockReturnValue(null);
    fireEvent.click(await screen.findByRole('button', { name: '정지' }));
    await waitFor(() => expect(mocked.changeStatus).toHaveBeenCalledTimes(1));
  });

  it('해지는 비가역이라 확인까지 받는다 — 거부하면 나가지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    await lookupAccount();
    fireEvent.click(await screen.findByRole('button', { name: '해지' }));

    await waitFor(() => expect(mocked.changeStatus).not.toHaveBeenCalled());
    expect(String(confirmSpy.mock.calls.at(-1)?.[0])).toMatch(/되돌릴 수 없습니다/);
  });

  it('정지 카드에는 재개가, 해지 카드에는 아무 조작도 없다', async () => {
    mocked.listCards.mockResolvedValue([
      card({ id: 12, status: 'SUSPENDED' }),
      card({ id: 13, status: 'CANCELED' }),
    ]);
    renderPage();
    await lookupAccount();

    expect(await screen.findByRole('button', { name: '재개' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '정지' })).not.toBeInTheDocument();
    // 해지 카드 행: 한도 변경·해지 버튼이 한 세트만 보인다(SUSPENDED 카드 몫)
    expect(screen.getAllByRole('button', { name: '해지' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '한도 변경' })).toHaveLength(1);
  });
});
