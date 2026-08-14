import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import SettlementPrintPage from '@/pages/print/SettlementPrintPage';
import { settlementApi } from '@/api/settlement';
import { authApi } from '@/api/auth';
import { putPrintHandoff, PRINT_DOC } from '@/lib/printHandoff';

vi.mock('@/api/settlement', () => ({
  settlementApi: { getSettlement: vi.fn(), search: vi.fn(), searchByPost: vi.fn() },
}));
vi.mock('@/api/auth', () => ({ authApi: { getCurrentUser: vi.fn() } }));

const mocked = vi.mocked(settlementApi);
const mockedAuth = vi.mocked(authApi);

const detail = (over: Record<string, unknown> = {}) =>
  ({
    id: 55,
    paymentId: 42,
    orderId: 100,
    paymentAmount: 20000,
    commission: 700,
    netAmount: 19300,
    status: 'DONE',
    settlementDate: '2026-08-01',
    confirmedAt: '2026-08-02T10:00:00',
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-02T10:00:00',
    ...over,
  }) as never;

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/print/settlement/:id" element={<SettlementPrintPage />} />
      </Routes>
    </MemoryRouter>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  mocked.getSettlement.mockResolvedValue(detail());
  mockedAuth.getCurrentUser.mockReturnValue({ id: 1, email: 'admin@example.com', role: 'ADMIN' } as never);
  vi.stubGlobal('print', vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SettlementPrintPage', () => {
  it('정산 상세를 스스로 재조회해 문서를 그린다 (목록 금액을 넘겨받지 않는다)', async () => {
    renderAt('/print/settlement/55?auto=0');

    expect(await screen.findByText('정 산 서')).toBeInTheDocument();
    expect(mocked.getSettlement).toHaveBeenCalledWith(55);
    expect(screen.getByText('ST-00000055')).toBeInTheDocument();
  });

  it('수수료율은 결제금액 대비로 계산해 함께 적는다', async () => {
    renderAt('/print/settlement/55?auto=0');

    expect(await screen.findByText(/3\.50%/)).toBeInTheDocument();
  });

  it('결제금액이 0이면 수수료율 표기를 생략한다 (0으로 나누지 않는다)', async () => {
    mocked.getSettlement.mockResolvedValue(detail({ paymentAmount: 0, commission: 0, netAmount: 0 }));
    renderAt('/print/settlement/55?auto=0');

    await screen.findByText('정 산 서');
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('정산 번호가 숫자가 아니면 조회하지 않고 안내한다', async () => {
    renderAt('/print/settlement/abc?auto=0');

    expect(await screen.findByText('정산 번호가 올바르지 않습니다.')).toBeInTheDocument();
    expect(mocked.getSettlement).not.toHaveBeenCalled();
  });

  it('응답이 정산 상세가 아니면 NaN 을 찍지 않고 재시도를 안내한다', async () => {
    mocked.getSettlement.mockResolvedValue({ notASettlement: true } as never);
    renderAt('/print/settlement/55?auto=0');

    expect(
      await screen.findByText(/인쇄할 수 있는 형태로 받지 못했습니다/),
    ).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다', async () => {
    mocked.getSettlement.mockRejectedValue({ response: { data: { message: '권한 없음' } } });
    renderAt('/print/settlement/55?auto=0');

    expect(await screen.findByText('권한 없음')).toBeInTheDocument();
  });

  it('여는 쪽이 심어 둔 보조 표시값(주문자·상품명)을 함께 인쇄한다', async () => {
    putPrintHandoff(PRINT_DOC.settlement, 55, { ordererName: '홍길동', productName: '티셔츠' });
    renderAt('/print/settlement/55?auto=0');

    expect(await screen.findByText('홍길동')).toBeInTheDocument();
    expect(screen.getByText('티셔츠')).toBeInTheDocument();
  });

  it('auto=0 이면 자동 인쇄를 띄우지 않는다', async () => {
    renderAt('/print/settlement/55?auto=0');
    await screen.findByText('정 산 서');

    await new Promise((r) => setTimeout(r, 30));
    expect(window.print).not.toHaveBeenCalled();
  });

  it('기본은 자동 인쇄 — 내용이 확정된 뒤에 대화상자를 띄운다', async () => {
    renderAt('/print/settlement/55');

    await waitFor(() => expect(window.print).toHaveBeenCalled(), { timeout: 3000 });
  });
});
