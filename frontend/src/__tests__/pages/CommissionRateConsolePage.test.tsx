import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import CommissionRateConsolePage from '@/pages/settlement/CommissionRateConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { commissionRateApi, percentToRate } from '@/api/commissionRate';

/**
 * 수수료율은 정산 금액을 직접 바꾼다. 이 화면이 지켜야 하는 규율 세 가지를 테스트로 못박는다.
 *   ① 변경은 close + 신규 등록이다(행 UPDATE 없음) — 화면이 "수정" 을 흉내 내면 이력이 망가진다.
 *   ② 정책은 미래에만 건다 — 소급 400 은 오류 문구가 아니라 정식 경로(역정산)를 알려 줘야 한다.
 *   ③ 이미 만들어진 정산은 스냅샷이라 바뀌지 않는다 — 운영자가 소급 효과를 기대하면 안 된다.
 */
vi.mock('@/api/commissionRate', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/commissionRate')>();
  return {
    ...actual,
    commissionRateApi: { list: vi.fn(), register: vi.fn(), close: vi.fn(), simulate: vi.fn() },
  };
});

const mocked = vi.mocked(commissionRateApi);

const policy = (over: Partial<Awaited<ReturnType<typeof commissionRateApi.list>>[number]> = {}) => ({
  id: 5, scope: 'SELLER' as const, scopeKey: '77', rate: '0.02500',
  effectiveFrom: '2026-09-01', effectiveTo: null,
  reason: '계약 갱신', createdBy: 'admin',
  createdAt: '2026-08-20T00:00:00Z', closedAt: null, closed: false, ...over,
});

const renderPage = () => render(<ToastProvider><CommissionRateConsolePage /></ToastProvider>);

const fillNewPolicy = () => {
  fireEvent.change(screen.getByPlaceholderText('셀러 ID 또는 등급'), { target: { value: '77' } });
  fireEvent.change(screen.getByPlaceholderText('예: 2.5'), { target: { value: '2.5' } });
  fireEvent.change(screen.getByLabelText('발효일'), { target: { value: '2026-10-01' } });
  fireEvent.change(screen.getByPlaceholderText('왜 이 요율인가'), { target: { value: '계약 갱신' } });
};

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.list.mockResolvedValue([policy()]);
  mocked.register.mockResolvedValue(policy({ id: 6, effectiveFrom: '2026-10-01' }));
  mocked.close.mockResolvedValue(undefined);
  mocked.simulate.mockResolvedValue({
    sellerId: 77, tier: 'VIP', at: '2026-09-01', rate: '0.02500', source: 'SELLER:77',
  });
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => confirmSpy.mockRestore());

describe('percentToRate — 부동소수를 쓰지 않는다', () => {
  it('퍼센트를 소수 문자열로 정확히 옮긴다', () => {
    expect(percentToRate('2.5')).toBe('0.025');
    expect(percentToRate('3.5')).toBe('0.035');
    expect(percentToRate('0.5')).toBe('0.005');
    expect(percentToRate('10')).toBe('0.10');
    expect(percentToRate('100')).toBe('1.00');
  });

  it('숫자로 읽을 수 없으면 null 이다 — 요율이 미상인 채 등록되지 않게', () => {
    expect(percentToRate('abc')).toBeNull();
    expect(percentToRate('')).toBeNull();
    expect(percentToRate('2.5%')).toBeNull();
  });
});

describe('CommissionRateConsolePage — 목록', () => {
  it('진입하면 살아 있는 정책만 먼저 보여 준다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith(false));
    expect(await screen.findByText('SELLER:77')).toBeInTheDocument();
  });

  it('요율을 퍼센트로 읽어 준다 — 0.025 를 눈으로 해석하게 두지 않는다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('2.5%')).toBeInTheDocument());
  });

  it('감사 근거(reason·등록자)를 목록에 함께 노출한다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('계약 갱신')).toBeInTheDocument());
    expect(screen.getByText(/admin/)).toBeInTheDocument();
  });

  it('종료 이력 토글을 켜면 닫힌 정책까지 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByLabelText('종료된 정책도 보기'));

    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith(true));
  });

  it('종료된 정책에는 종료 버튼을 주지 않는다 — 눌러도 거부될 버튼을 남기지 않는다', async () => {
    mocked.list.mockResolvedValue([policy({ closed: true, closedAt: '2026-08-30T00:00:00Z' })]);
    renderPage();

    await waitFor(() => expect(screen.getByText('종료됨')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '종료' })).not.toBeInTheDocument();
  });

  it('정책이 하나도 없으면 등급 기본율로 동작한다는 사실을 알린다', async () => {
    mocked.list.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/등급 기본율/)).toBeInTheDocument());
  });
});

describe('CommissionRateConsolePage — 종료(close)', () => {
  it('종료는 확인을 받는다 — 변경은 close + 신규 등록이라는 사실을 확인창에 적는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    await waitFor(() => expect(screen.getByText('SELLER:77')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '종료' }));

    await waitFor(() => expect(mocked.close).not.toHaveBeenCalled());
    expect(String(confirmSpy.mock.calls[0][0])).toMatch(/새 정책/);
  });

  it('확인하면 종료하고 목록을 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('SELLER:77')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '종료' }));

    await waitFor(() => expect(mocked.close).toHaveBeenCalledWith(5));
    expect(mocked.list).toHaveBeenCalledTimes(2);
  });
});

describe('CommissionRateConsolePage — 등록', () => {
  it('퍼센트 입력을 소수로 바꿔 보낸다 (2.5 → 0.025)', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fillNewPolicy();
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith(expect.objectContaining({
      scope: 'SELLER', scopeKey: '77', rate: '0.025',
      effectiveFrom: '2026-10-01', reason: '계약 갱신',
    })));
  });

  it('사유가 비면 서버를 부르지 않는다 — 감사 없이 요율이 바뀌지 않게', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fillNewPolicy();
    fireEvent.change(screen.getByPlaceholderText('왜 이 요율인가'), { target: { value: '  ' } });
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(mocked.register).not.toHaveBeenCalled());
  });

  it('요율이 숫자가 아니면 서버를 부르지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fillNewPolicy();
    fireEvent.change(screen.getByPlaceholderText('예: 2.5'), { target: { value: '이점오' } });
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(mocked.register).not.toHaveBeenCalled());
  });

  it('등록은 확인을 받고, 확인창은 과거 정산이 바뀌지 않음을 알린다 (스냅샷 보존)', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fillNewPolicy();
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(mocked.register).not.toHaveBeenCalled());
    expect(String(confirmSpy.mock.calls[0][0])).toMatch(/이미 생성된 정산/);
  });

  it('소급 거부(400)는 정식 경로인 역정산을 알려 준다', async () => {
    mocked.register.mockRejectedValue({
      response: { status: 400, data: { message: '소급 구간에 이미 생성된 정산이 3건 있습니다. SettlementAdjustment(역정산)를 사용하세요.' } },
    });
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fillNewPolicy();
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(screen.getByText(/역정산/)).toBeInTheDocument());
  });

  it('등록에 성공하면 목록을 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));
    fillNewPolicy();
    fireEvent.click(screen.getByRole('button', { name: '정책 등록' }));

    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });
});

describe('CommissionRateConsolePage — 해석 미리보기', () => {
  it('무엇이 이겼는지(source)를 요율과 함께 보여 준다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fireEvent.change(screen.getByPlaceholderText('셀러 ID(선택)'), { target: { value: '77' } });
    fireEvent.click(screen.getByRole('button', { name: '요율 확인' }));

    await waitFor(() => expect(mocked.simulate).toHaveBeenCalledWith(
      expect.objectContaining({ sellerId: 77 })));
    const panel = screen.getByTestId('simulation-result');
    expect(within(panel).getByText('SELLER:77')).toBeInTheDocument();
    expect(within(panel).getByText('2.5%')).toBeInTheDocument();
  });

  it('셀러를 비우면 등급만으로 조회한다 — 등급 기본율 확인 경로다', async () => {
    mocked.simulate.mockResolvedValue({
      sellerId: null, tier: 'NORMAL', at: '2026-09-01', rate: '0.03500', source: 'DEFAULT_TIER',
    });
    renderPage();
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '요율 확인' }));

    await waitFor(() => expect(mocked.simulate).toHaveBeenCalled());
    expect(mocked.simulate.mock.calls[0][0].sellerId).toBeUndefined();
    expect(within(screen.getByTestId('simulation-result')).getByText('DEFAULT_TIER')).toBeInTheDocument();
  });
});
