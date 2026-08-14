import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from '@/api/axios';
import { reviewQueueApi } from '@/api/reviewQueue';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const mockedGet = vi.mocked(api.get);
const mockedPost = vi.mocked(api.post);

/**
 * 리뷰 큐 API 모듈 — 4개 서비스의 경로·파라미터 계약을 그대로 고정한다.
 * 경로가 어긋나면 gateway 라우팅(card=/admin/expense-receipts predicate 등)과 조용히 어긋난다.
 */
describe('reviewQueueApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedGet.mockResolvedValue({ data: [] });
    mockedPost.mockResolvedValue({ data: {} });
  });

  it('목록 4종은 각 서비스 경로에 status·limit 파라미터로 조회한다 (기본 NEEDS_REVIEW/50)', async () => {
    await reviewQueueApi.listCardReceipts();
    await reviewQueueApi.listInsuranceDocuments();
    await reviewQueueApi.listLoanCollateralDocuments();
    await reviewQueueApi.listDepositProofs();

    const expectedParams = { params: { status: 'NEEDS_REVIEW', limit: 50 } };
    expect(mockedGet).toHaveBeenCalledWith('/admin/expense-receipts', expectedParams);
    expect(mockedGet).toHaveBeenCalledWith('/api/insurance/application-documents', expectedParams);
    expect(mockedGet).toHaveBeenCalledWith('/loans/secured/collateral-documents', expectedParams);
    expect(mockedGet).toHaveBeenCalledWith('/admin/deposits/proofs', expectedParams);
  });

  it('상태·limit 을 지정하면 그대로 전달한다', async () => {
    await reviewQueueApi.listCardReceipts('MISMATCHED', 10);

    expect(mockedGet).toHaveBeenCalledWith('/admin/expense-receipts', {
      params: { status: 'MISMATCHED', limit: 10 },
    });
  });

  it('리뷰 종결 4종은 각 서비스 review 경로에 matched·note 를 보낸다', async () => {
    const payload = { matched: true, note: '육안 대조 완료' };

    await reviewQueueApi.reviewCardReceipt(1, payload);
    await reviewQueueApi.reviewInsuranceDocument(2, payload);
    await reviewQueueApi.reviewLoanCollateralDocument(3, payload);
    await reviewQueueApi.reviewDepositProof(4, payload);

    expect(mockedPost).toHaveBeenCalledWith('/admin/expense-receipts/1/review', payload);
    expect(mockedPost).toHaveBeenCalledWith('/api/insurance/application-documents/2/review', payload);
    expect(mockedPost).toHaveBeenCalledWith('/loans/secured/collateral-documents/3/review', payload);
    expect(mockedPost).toHaveBeenCalledWith('/admin/deposits/proofs/4/review', payload);
  });
});
