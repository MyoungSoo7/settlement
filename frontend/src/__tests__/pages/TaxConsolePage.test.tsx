import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import TaxConsolePage from '@/pages/settlement/TaxConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { taxApi } from '@/api/tax';

/**
 * 세무 콘솔은 두 종류의 위험을 함께 다룬다.
 *   ① 전표 전기·세금계산서 발행은 <b>원장과 세무 산출물을 만드는 실행</b>이다 — 되돌리기 어렵다.
 *   ② sellerId 를 운영자가 입력하는데, 서버는 정산의 실제 소유 셀러와 대조해 403 을 준다(IDOR).
 * 그래서 확인 절차와 403 구분 표시를 테스트로 못박는다.
 */
vi.mock('@/api/tax', () => ({
  taxApi: {
    scans: vi.fn(), rejectScan: vi.fn(), rematchScan: vi.fn(),
    profile: vi.fn(), upsertProfile: vi.fn(),
    reconcile: vi.fn(), post: vi.fn(), issue: vi.fn(), invoice: vi.fn(),
    invoicePdfUrl: (id: number) => `/admin/tax/settlements/${id}/invoice.pdf`,
  },
}));

const mocked = vi.mocked(taxApi);

const scan = (over: Partial<Awaited<ReturnType<typeof taxApi.scans>>[number]> = {}) => ({
  id: 7, status: 'MISMATCHED' as const, fileName: 'inv-2026-08.pdf', ocrModel: 'gemini',
  supplierBusinessNo: '123-45-*****', buyerBusinessNo: '987-65-*****',
  writtenDate: '2026-08-01', supplyAmount: '1000000', taxAmount: '100000', totalAmount: '1100000',
  approvalNumber: 'A-1', confidence: '0.62', needsReview: true,
  totalConsistent: true, vatConsistent: false, linkedTaxInvoiceId: 3, reviewNote: null,
  createdAt: '2026-08-10T09:00:00Z', ...over,
});

const recon = (over = {}) => ({
  matched: true, ledgerBalanced: true,
  ledgerVatAccrued: '100000', actualWithholdingDeducted: '33000',
  checks: [
    { name: 'VAT_AMOUNT', expected: '100000', actual: '100000', passed: true },
    { name: 'WITHHOLDING', expected: '33000', actual: '30000', passed: false },
  ],
  ...over,
});

const invoice = () => ({
  settlementId: 55, sellerId: 42, issueNumber: 'TI-2026-0001',
  supplyAmount: '1000000', taxAmount: '100000', totalAmount: '1100000', issueDate: '2026-08-10',
});

const renderPage = () => render(<ToastProvider><TaxConsolePage /></ToastProvider>);

/** 정산 탭으로 이동해 정산·셀러를 지정하고 조회한다. */
const lookupSettlement = (settlementId = '55', sellerId = '42') => {
  fireEvent.click(screen.getByRole('button', { name: '정산별 세무' }));
  fireEvent.change(screen.getByPlaceholderText('정산 ID'), { target: { value: settlementId } });
  fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: sellerId } });
  fireEvent.click(screen.getByRole('button', { name: '대사 조회' }));
};

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.scans.mockResolvedValue([scan()]);
  mocked.reconcile.mockResolvedValue(recon());
  mocked.invoice.mockResolvedValue(invoice());
  mocked.post.mockResolvedValue({ outcome: 'POSTED', entriesPosted: 2, vatAmount: '100000', withholdingAmount: '33000' });
  mocked.issue.mockResolvedValue(invoice());
  mocked.profile.mockResolvedValue({ sellerId: 42, taxType: 'INDIVIDUAL', businessRegNo: '123-45-*****', updatedAt: '2026-08-01T00:00:00Z' });
  mocked.upsertProfile.mockResolvedValue({ sellerId: 42, taxType: 'BUSINESS', businessRegNo: '123-45-*****', updatedAt: '2026-08-11T00:00:00Z' });
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => confirmSpy.mockRestore());

describe('TaxConsolePage — 스캔 리뷰 큐', () => {
  it('진입하면 사람 손이 필요한 상태(MISMATCHED)를 먼저 띄운다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.scans).toHaveBeenCalledWith('MISMATCHED', 50));
    expect(screen.getByText('inv-2026-08.pdf')).toBeInTheDocument();
  });

  it('OCR 이 미심쩍은 건을 근거와 함께 드러낸다 — 신뢰도·산술 불일치', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText(/확인 필요/)).toBeInTheDocument());
    // 세액이 공급가의 10% 가 아니다 — 이 사실이 화면에 보여야 사람이 판단할 수 있다
    expect(screen.getByText(/세액 불일치/)).toBeInTheDocument();
  });

  it('반려는 사유가 비면 서버를 부르지 않는다 (감사 근거가 남지 않는다)', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('inv-2026-08.pdf')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '반려' }));

    await waitFor(() => expect(mocked.rejectScan).not.toHaveBeenCalled());
  });

  it('사유를 적으면 반려하고 큐를 다시 읽는다', async () => {
    mocked.rejectScan.mockResolvedValue(scan({ status: 'REJECTED' }));
    renderPage();
    await waitFor(() => expect(screen.getByText('inv-2026-08.pdf')).toBeInTheDocument());
    fireEvent.change(screen.getByPlaceholderText('반려 사유'), { target: { value: '위조 의심' } });
    fireEvent.click(screen.getByRole('button', { name: '반려' }));

    await waitFor(() => expect(mocked.rejectScan).toHaveBeenCalledWith(7, '위조 의심'));
    expect(mocked.scans).toHaveBeenCalledTimes(2);
  });

  it('재대사는 확인 없이 곧바로 부른다 — 되돌릴 수 있는 조회성 실행이다', async () => {
    mocked.rematchScan.mockResolvedValue(scan({ status: 'MATCHED' }));
    renderPage();
    await waitFor(() => expect(screen.getByText('inv-2026-08.pdf')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재대사' }));

    await waitFor(() => expect(mocked.rematchScan).toHaveBeenCalledWith(7));
  });

  it('상태를 바꾸면 그 상태로 다시 조회한다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.scans).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText('상태'), { target: { value: 'UNMATCHED' } });

    await waitFor(() => expect(mocked.scans).toHaveBeenCalledWith('UNMATCHED', 50));
  });

  it('큐가 비면 빈 상태를 명시한다', async () => {
    mocked.scans.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/리뷰할 스캔이 없습니다/)).toBeInTheDocument());
  });
});

describe('TaxConsolePage — 정산별 세무', () => {
  it('정산·셀러를 지정해야 조회한다 — 빈 조회로 전체를 훑지 않는다', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '정산별 세무' }));
    fireEvent.click(screen.getByRole('button', { name: '대사 조회' }));

    await waitFor(() => expect(mocked.reconcile).not.toHaveBeenCalled());
  });

  it('대사 결과의 실패 항목을 감추지 않고 드러낸다', async () => {
    renderPage();
    lookupSettlement();

    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalledWith(55, 42));
    expect(screen.getByText('WITHHOLDING')).toBeInTheDocument();
    expect(screen.getByText(/불일치 1건/)).toBeInTheDocument();
  });

  it('전표 전기는 확인을 받는다 — 원장을 움직이는 실행이다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    lookupSettlement();
    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '전표 전기' }));

    await waitFor(() => expect(mocked.post).not.toHaveBeenCalled());
    expect(confirmSpy).toHaveBeenCalled();
  });

  it('확인하면 전기하고 대사를 다시 읽는다 — 전기 후 상태가 달라진다', async () => {
    renderPage();
    lookupSettlement();
    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: '전표 전기' }));

    await waitFor(() => expect(mocked.post).toHaveBeenCalledWith(55, 42));
    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalledTimes(2));
  });

  it('세금계산서 발행도 확인을 받는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    lookupSettlement();
    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '세금계산서 발행' }));

    await waitFor(() => expect(mocked.issue).not.toHaveBeenCalled());
  });

  it('이미 발행된 건(409)은 장애가 아니라 상태로 알린다', async () => {
    mocked.issue.mockRejectedValue({ response: { status: 409 } });
    renderPage();
    lookupSettlement();
    await waitFor(() => expect(mocked.reconcile).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '세금계산서 발행' }));

    await waitFor(() => expect(screen.getByText(/이미 발행/)).toBeInTheDocument());
  });

  it('403 은 장애가 아니라 셀러 지정 오류로 구분해 보여 준다 (IDOR 방어의 표면)', async () => {
    mocked.reconcile.mockRejectedValue({ response: { status: 403 } });
    renderPage();
    lookupSettlement('55', '999');

    await waitFor(() => expect(screen.getByText(/셀러가 이 정산의 소유자가 아닙니다/)).toBeInTheDocument());
  });

  it('발행된 세금계산서가 있으면 PDF 진입점을 준다', async () => {
    renderPage();
    lookupSettlement();

    await waitFor(() => expect(screen.getByText('TI-2026-0001')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /PDF/ }))
      .toHaveAttribute('href', '/admin/tax/settlements/55/invoice.pdf');
  });

  it('미발행(404)은 빈 상태로 두고 발행 버튼을 남긴다', async () => {
    mocked.invoice.mockRejectedValue({ response: { status: 404 } });
    renderPage();
    lookupSettlement();

    await waitFor(() => expect(screen.getByText(/아직 발행되지 않았습니다/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '세금계산서 발행' })).toBeEnabled();
  });
});

describe('TaxConsolePage — 셀러 세무 프로필', () => {
  it('셀러를 지정해 프로필을 조회한다', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '세무 프로필' }));
    fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: '프로필 조회' }));

    await waitFor(() => expect(mocked.profile).toHaveBeenCalledWith(42));
    // 세무유형은 조회 패널과 편집 셀렉트 양쪽에 나온다 — 조회 결과 쪽을 본다.
    expect(within(screen.getByTestId('tax-profile')).getByText('INDIVIDUAL')).toBeInTheDocument();
  });

  it('사업자등록번호는 마스킹된 값 그대로만 보여 준다', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '세무 프로필' }));
    fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: '프로필 조회' }));

    await waitFor(() => expect(screen.getByText('123-45-*****')).toBeInTheDocument());
  });

  it('개인 → 사업자 변경은 원천징수 대상이 바뀐다고 경고하고 확인을 받는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '세무 프로필' }));
    fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: '프로필 조회' }));
    await waitFor(() => expect(mocked.profile).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText('세무유형'), { target: { value: 'BUSINESS' } });
    fireEvent.change(screen.getByPlaceholderText('사업자등록번호'), { target: { value: '1234567890' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mocked.upsertProfile).not.toHaveBeenCalled());
    expect(String(confirmSpy.mock.calls[0][0])).toMatch(/원천징수/);
  });

  it('확인하면 저장하고 결과를 반영한다', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '세무 프로필' }));
    fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: '프로필 조회' }));
    await waitFor(() => expect(mocked.profile).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText('세무유형'), { target: { value: 'BUSINESS' } });
    fireEvent.change(screen.getByPlaceholderText('사업자등록번호'), { target: { value: '1234567890' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mocked.upsertProfile).toHaveBeenCalledWith(42, 'BUSINESS', '1234567890'));
    await waitFor(() => expect(within(screen.getByTestId('tax-profile')).getByText('BUSINESS')).toBeInTheDocument());
  });

  it('미등록 셀러(404)는 오류가 아니라 신규 등록 안내로 떨어진다', async () => {
    mocked.profile.mockRejectedValue({ response: { status: 404 } });
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '세무 프로필' }));
    fireEvent.change(screen.getByPlaceholderText('셀러 ID'), { target: { value: '77' } });
    fireEvent.click(screen.getByRole('button', { name: '프로필 조회' }));

    await waitFor(() => expect(screen.getByText(/등록된 세무 프로필이 없습니다/)).toBeInTheDocument());
  });
});
