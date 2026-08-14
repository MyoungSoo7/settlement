import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import ProofReviewQueuePage from '@/pages/ProofReviewQueuePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { reviewQueueApi } from '@/api/reviewQueue';

vi.mock('@/api/reviewQueue', () => ({
  reviewQueueApi: {
    listCardReceipts: vi.fn(),
    reviewCardReceipt: vi.fn(),
    listInsuranceDocuments: vi.fn(),
    reviewInsuranceDocument: vi.fn(),
    listLoanCollateralDocuments: vi.fn(),
    reviewLoanCollateralDocument: vi.fn(),
    listDepositProofs: vi.fn(),
    reviewDepositProof: vi.fn(),
  },
}));

const mocked = vi.mocked(reviewQueueApi);

const cardReceipt = {
  id: 11,
  reportId: 'RPT-1',
  captureId: 'CAP-1',
  status: 'NEEDS_REVIEW' as const,
  merchantName: '김밥천국',
  transactionDate: '2026-08-10',
  totalAmount: '12000',
  confidence: '0.50',
  matchNote: '신뢰도 미달',
  ocrModel: 'gemini-2.5-flash',
  fileName: 'receipt.jpg',
  reviewedBy: null,
  createdAt: '2026-08-14T10:00:00Z',
};

const depositProof = {
  id: 22,
  sellerId: 7,
  referenceType: 'MANUAL_TOPUP',
  referenceId: 'TOPUP-001',
  status: 'NEEDS_REVIEW' as const,
  senderName: '홍길동',
  transferDate: null,
  transferAmount: '3000000',
  confidence: '0.93',
  matchNote: '이체일 판독 불가',
  ocrModel: 'gemini-2.5-flash',
  fileName: '이체확인증.png',
  reviewedBy: null,
  createdAt: '2026-08-14T10:00:00',
};

const renderPage = () => render(
  <ToastProvider>
    <ProofReviewQueuePage />
  </ToastProvider>,
);

describe('ProofReviewQueuePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.listCardReceipts.mockResolvedValue([cardReceipt]);
    mocked.listInsuranceDocuments.mockResolvedValue([]);
    mocked.listLoanCollateralDocuments.mockResolvedValue([]);
    mocked.listDepositProofs.mockResolvedValue([depositProof]);
  });

  it('기본 탭(카드 영수증)의 NEEDS_REVIEW 목록을 그린다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.listCardReceipts).toHaveBeenCalled());
    const row = (await screen.findByText('RPT-1')).closest('tr')!;
    expect(within(row).getByText('김밥천국')).toBeInTheDocument();
    expect(within(row).getByText('12000')).toBeInTheDocument();
    expect(within(row).getByText('신뢰도 미달')).toBeInTheDocument();
  });

  it('근거(note)를 입력하기 전에는 확정·반려 버튼이 비활성이다 — 게이트를 여는 판단에는 근거가 필수', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('RPT-1')).toBeInTheDocument());
    const row = screen.getByText('RPT-1').closest('tr')!;

    expect(within(row).getByText('확정')).toBeDisabled();

    fireEvent.change(within(row).getByLabelText('리뷰 근거 11'), { target: { value: '영수증 육안 대조' } });
    expect(within(row).getByText('확정')).toBeEnabled();
  });

  it('확정을 누르면 리뷰 API 를 부르고 목록을 재조회한다', async () => {
    mocked.reviewCardReceipt.mockResolvedValue({ ...cardReceipt, status: 'MATCHED' });
    renderPage();
    await waitFor(() => expect(screen.getByText('RPT-1')).toBeInTheDocument());
    const row = screen.getByText('RPT-1').closest('tr')!;

    fireEvent.change(within(row).getByLabelText('리뷰 근거 11'), { target: { value: '영수증 육안 대조' } });
    fireEvent.click(within(row).getByText('확정'));

    await waitFor(() => expect(mocked.reviewCardReceipt).toHaveBeenCalledWith(11, {
      matched: true,
      note: '영수증 육안 대조',
    }));
    await waitFor(() => expect(mocked.listCardReceipts).toHaveBeenCalledTimes(2));
  });

  it('예치금 탭으로 전환하면 deposit 목록을 조회해 참조 키를 그린다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('RPT-1')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '예치금 증빙' }));

    await waitFor(() => expect(mocked.listDepositProofs).toHaveBeenCalled());
    expect(await screen.findByText('MANUAL_TOPUP/TOPUP-001')).toBeInTheDocument();
    expect(screen.getByText('이체일 판독 불가')).toBeInTheDocument();
  });

  it('빈 큐는 빈 상태 문구를 보여준다', async () => {
    mocked.listCardReceipts.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText('리뷰 대기 증빙이 없습니다.')).toBeInTheDocument());
  });
});
