import api from './axios';

/**
 * 세무 운영 API — settlement-service 의 세무 표면 3종을 한 모듈로 모은다.
 *
 *   `/admin/tax/scans/**`             세금계산서 스캔 리뷰 큐(OCR 이 사람 판단을 대체하지 않는다)
 *   `/admin/tax/settlements/{id}/**`  정산별 세무 전표 전기 · 세금계산서 발행 · 3자 대사
 *   `/admin/seller-tax-profiles/**`   셀러 세무유형(개인/사업자) 등록·정정
 *
 * <p>인가는 전부 ADMIN/MANAGER 다. 정산별 표면은 그 위에 <b>소유권 대조</b>가 한 겹 더 있어서,
 * 요청 sellerId 가 정산의 실제 소유 셀러와 다르면 403 이 온다(IDOR 방지) — 화면은 이 403 을
 * 장애가 아니라 "셀러를 잘못 지정했다"로 구분해 보여 줘야 한다.
 *
 * <p>사업자등록번호는 서버가 <b>마스킹된 값만</b> 내려준다. 원문을 요구하거나 재구성하지 않는다.
 */

export type TaxScanStatus = 'EXTRACTED' | 'MATCHED' | 'MISMATCHED' | 'UNMATCHED' | 'REJECTED';

export type TaxType = 'INDIVIDUAL' | 'BUSINESS';

export interface TaxInvoiceScan {
  id: number;
  status: TaxScanStatus;
  fileName: string | null;
  ocrModel: string | null;
  /** 마스킹된 공급자 사업자등록번호 */
  supplierBusinessNo: string | null;
  buyerBusinessNo: string | null;
  writtenDate: string | null;
  supplyAmount: string | null;
  taxAmount: string | null;
  totalAmount: string | null;
  approvalNumber: string | null;
  /** OCR 신뢰도(0~1) */
  confidence: string | null;
  /** 신뢰도가 임계값 아래 — 사람이 봐야 한다 */
  needsReview: boolean;
  /** 공급가+세액 = 합계 가 맞는가 */
  totalConsistent: boolean;
  /** 세액 = 공급가의 10% 가 맞는가 */
  vatConsistent: boolean;
  linkedTaxInvoiceId: number | null;
  reviewNote: string | null;
  createdAt: string | null;
}

export interface SellerTaxProfile {
  sellerId: number;
  taxType: TaxType;
  /** 마스킹된 값 */
  businessRegNo: string | null;
  updatedAt: string | null;
}

export interface TaxPosting {
  outcome: string;
  entriesPosted: number;
  vatAmount: string | null;
  withholdingAmount: string | null;
}

export interface TaxInvoice {
  settlementId: number;
  sellerId: number;
  issueNumber: string;
  supplyAmount: string;
  taxAmount: string;
  totalAmount: string;
  issueDate: string;
}

export interface TaxReconciliationCheck {
  name: string;
  expected: string | null;
  actual: string | null;
  passed: boolean;
}

export interface TaxReconciliation {
  matched: boolean;
  ledgerBalanced: boolean;
  ledgerVatAccrued: string | null;
  actualWithholdingDeducted: string | null;
  checks: TaxReconciliationCheck[];
}

export const taxApi = {
  /**
   * 스캔 리뷰 큐 — 상태 하나 또는 여럿을 한 번에 조회한다.
   *
   * 사람 손이 필요한 상태는 셋이다(보류·불일치·미매칭). 화면을 상태별로 쪼개면 한 곳만 보다가
   * 나머지에 쌓인 건을 놓친다. 서버는 `?status=A&status=B` 형태로 받는다.
   */
  scans: async (status: TaxScanStatus | TaxScanStatus[], limit = 50): Promise<TaxInvoiceScan[]> =>
    (await api.get<TaxInvoiceScan[]>('/admin/tax/scans', {
      params: { status, limit },
      // axios 기본은 status[]=A 로 직렬화한다 — 스프링이 못 읽으므로 반복 키로 편다.
      paramsSerializer: { indexes: null },
    })).data,

  /** 스캔 반려(종결) — 사유는 감사 근거라 비워 보내지 않는다 */
  rejectScan: async (scanId: number, note: string): Promise<TaxInvoiceScan> =>
    (await api.post<TaxInvoiceScan>(`/admin/tax/scans/${scanId}/reject`, { note })).data,

  /** 재대사 — 발행분과 다시 맞춰 본다 */
  rematchScan: async (scanId: number): Promise<TaxInvoiceScan> =>
    (await api.post<TaxInvoiceScan>(`/admin/tax/scans/${scanId}/rematch`)).data,

  /** 셀러 세무 프로필 조회 — 미등록이면 404 */
  profile: async (sellerId: number): Promise<SellerTaxProfile> =>
    (await api.get<SellerTaxProfile>(`/admin/seller-tax-profiles/${sellerId}`)).data,

  /** 세무 프로필 등록·정정(upsert) */
  upsertProfile: async (sellerId: number, taxType: TaxType, businessRegNo: string): Promise<SellerTaxProfile> =>
    (await api.post<SellerTaxProfile>('/admin/seller-tax-profiles', { sellerId, taxType, businessRegNo })).data,

  /** 세무 3자 대사(계산·세금계산서·원장) — sellerId 불일치는 403 */
  reconcile: async (settlementId: number, sellerId: number): Promise<TaxReconciliation> =>
    (await api.get<TaxReconciliation>(`/admin/tax/settlements/${settlementId}/reconciliation`,
      { params: { sellerId } })).data,

  /** 세무 전표 전기(부가세 예수·원천징수) — 원장을 움직인다 */
  post: async (settlementId: number, sellerId: number): Promise<TaxPosting> =>
    (await api.post<TaxPosting>(`/admin/tax/settlements/${settlementId}/post`, null,
      { params: { sellerId } })).data,

  /** 세금계산서 발행 — 이미 발행됐으면 409 */
  issue: async (settlementId: number, sellerId: number): Promise<TaxInvoice> =>
    (await api.post<TaxInvoice>(`/admin/tax/settlements/${settlementId}/invoice`, null,
      { params: { sellerId } })).data,

  /** 발행된 세금계산서 조회 — 없으면 404 */
  invoice: async (settlementId: number): Promise<TaxInvoice> =>
    (await api.get<TaxInvoice>(`/admin/tax/settlements/${settlementId}/invoice`)).data,

  /** 세금계산서 PDF — 새 창으로 연다(브라우저가 그리게 둔다) */
  invoicePdfUrl: (settlementId: number): string =>
    `/admin/tax/settlements/${settlementId}/invoice.pdf`,
};
