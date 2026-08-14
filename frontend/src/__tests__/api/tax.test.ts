import { describe, it, expect, vi, beforeEach } from 'vitest';
import { taxApi, type TaxInvoiceScan } from '@/api/tax';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const scan: TaxInvoiceScan = {
  id: 3,
  status: 'EXTRACTED',
  fileName: 'invoice.png',
  ocrModel: 'gemini-2.0-flash',
  supplierBusinessNo: '123-**-*6789',
  buyerBusinessNo: null,
  writtenDate: '2026-08-01',
  supplyAmount: '1000000',
  taxAmount: '100000',
  totalAmount: '1100000',
  approvalNumber: null,
  confidence: '0.62',
  needsReview: true,
  totalConsistent: true,
  vatConsistent: true,
  linkedTaxInvoiceId: null,
  reviewNote: null,
  createdAt: '2026-08-01T00:00:00Z',
};

describe('taxApi 스캔 리뷰 큐', () => {
  beforeEach(() => vi.resetAllMocks());

  it('상태별 스캔을 기본 limit 50 으로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [scan] });

    const result = await taxApi.scans('EXTRACTED');

    expect(api.get).toHaveBeenCalledWith('/admin/tax/scans', {
      params: { status: 'EXTRACTED', limit: 50 },
    });
    expect(result[0].needsReview).toBe(true);
  });

  it('limit 을 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [] });

    await taxApi.scans('MISMATCHED', 10);

    expect(api.get).toHaveBeenCalledWith('/admin/tax/scans', {
      params: { status: 'MISMATCHED', limit: 10 },
    });
  });

  it('반려는 사유(note)를 함께 보낸다 — 감사 근거', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...scan, status: 'REJECTED' } });

    const result = await taxApi.rejectScan(3, '중복 업로드');

    expect(api.post).toHaveBeenCalledWith('/admin/tax/scans/3/reject', { note: '중복 업로드' });
    expect(result.status).toBe('REJECTED');
  });

  it('재대사를 요청한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...scan, status: 'MATCHED' } });

    const result = await taxApi.rematchScan(3);

    expect(api.post).toHaveBeenCalledWith('/admin/tax/scans/3/rematch');
    expect(result.status).toBe('MATCHED');
  });
});

describe('taxApi 셀러 세무 프로필', () => {
  beforeEach(() => vi.resetAllMocks());

  it('프로필을 조회한다 (사업자번호는 마스킹된 값 그대로 사용)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { sellerId: 1, taxType: 'BUSINESS', businessRegNo: '123-**-*6789', updatedAt: null },
    });

    const result = await taxApi.profile(1);

    expect(api.get).toHaveBeenCalledWith('/admin/seller-tax-profiles/1');
    expect(result.businessRegNo).toBe('123-**-*6789');
  });

  it('미등록 셀러는 404 가 전파된다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 404 } });

    await expect(taxApi.profile(999)).rejects.toMatchObject({ response: { status: 404 } });
  });

  it('프로필을 등록·정정한다(upsert)', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: { sellerId: 1, taxType: 'INDIVIDUAL', businessRegNo: null, updatedAt: '2026-08-01T00:00:00Z' },
    });

    await taxApi.upsertProfile(1, 'INDIVIDUAL', '1234567890');

    expect(api.post).toHaveBeenCalledWith('/admin/seller-tax-profiles', {
      sellerId: 1,
      taxType: 'INDIVIDUAL',
      businessRegNo: '1234567890',
    });
  });
});

describe('taxApi 정산별 세무 표면', () => {
  beforeEach(() => vi.resetAllMocks());

  it('3자 대사는 sellerId 를 쿼리로 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        matched: true,
        ledgerBalanced: true,
        ledgerVatAccrued: '100000',
        actualWithholdingDeducted: '0',
        checks: [{ name: 'VAT', expected: '100000', actual: '100000', passed: true }],
      },
    });

    const result = await taxApi.reconcile(55, 1);

    expect(api.get).toHaveBeenCalledWith('/admin/tax/settlements/55/reconciliation', {
      params: { sellerId: 1 },
    });
    expect(result.checks[0].passed).toBe(true);
  });

  it('소유권이 어긋난 sellerId 는 403 으로 구분된다 (IDOR 방지)', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 403 } });

    await expect(taxApi.reconcile(55, 9)).rejects.toMatchObject({ response: { status: 403 } });
  });

  it('세무 전표를 전기한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: { outcome: 'POSTED', entriesPosted: 2, vatAmount: '100000', withholdingAmount: null },
    });

    const result = await taxApi.post(55, 1);

    expect(api.post).toHaveBeenCalledWith('/admin/tax/settlements/55/post', null, {
      params: { sellerId: 1 },
    });
    expect(result.entriesPosted).toBe(2);
  });

  it('세금계산서를 발행한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        settlementId: 55,
        sellerId: 1,
        issueNumber: 'TI-2026-0001',
        supplyAmount: '1000000',
        taxAmount: '100000',
        totalAmount: '1100000',
        issueDate: '2026-08-01',
      },
    });

    const result = await taxApi.issue(55, 1);

    expect(api.post).toHaveBeenCalledWith('/admin/tax/settlements/55/invoice', null, {
      params: { sellerId: 1 },
    });
    expect(result.issueNumber).toBe('TI-2026-0001');
  });

  it('이미 발행된 계산서 재발행은 409 가 전파된다', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 409 } });

    await expect(taxApi.issue(55, 1)).rejects.toMatchObject({ response: { status: 409 } });
  });

  it('발행된 계산서를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        settlementId: 55,
        sellerId: 1,
        issueNumber: 'TI-2026-0001',
        supplyAmount: '1000000',
        taxAmount: '100000',
        totalAmount: '1100000',
        issueDate: '2026-08-01',
      },
    });

    const result = await taxApi.invoice(55);

    expect(api.get).toHaveBeenCalledWith('/admin/tax/settlements/55/invoice');
    expect(result.settlementId).toBe(55);
  });

  it('PDF 는 브라우저가 열 URL 만 만들어 준다 (호출하지 않는다)', () => {
    expect(taxApi.invoicePdfUrl(55)).toBe('/admin/tax/settlements/55/invoice.pdf');
    expect(api.get).not.toHaveBeenCalled();
  });
});
