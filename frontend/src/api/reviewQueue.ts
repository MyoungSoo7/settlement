import api from './axios';

/**
 * 증빙 OCR 리뷰 큐 API (ADR 0036) — 4개 서비스의 NEEDS_REVIEW 대기 목록·리뷰 종결.
 *
 * 서비스마다 경로 접두사가 다르다(card=/admin/expense-receipts, insurance=/api/insurance,
 * loan=/loans/secured, deposit=/admin/deposits). displaySection 이 admin/public 두 표면을 한 모듈에
 * 담은 것과 같은 방식으로 한 모듈에 모은다. 전부 gateway 경유(ADMIN 또는 ADMIN/MANAGER 게이트).
 */

export type ProofReviewStatus = 'EXTRACTED' | 'MATCHED' | 'MISMATCHED' | 'NEEDS_REVIEW';

export interface CardReceiptItem {
  id: number;
  reportId: string;
  captureId: string;
  status: ProofReviewStatus;
  merchantName: string | null;
  transactionDate: string | null;
  totalAmount: string;
  confidence: string;
  matchNote: string | null;
  ocrModel: string;
  fileName: string;
  reviewedBy: number | null;
  createdAt: string;
}

export interface InsuranceDocumentItem {
  id: number;
  applicationId: string;
  status: ProofReviewStatus;
  contractorName: string | null;
  insuredName: string | null;
  productName: string | null;
  applicationDate: string | null;
  annualPremium: string;
  coverageAmount: string | null;
  confidence: string;
  matchNote: string | null;
  ocrModel: string;
  fileName: string;
  reviewedBy: string | null;   // insurance 만 문자열(FC 식별자)
  createdAt: string;
}

export interface LoanCollateralDocumentItem {
  id: number;
  securedLoanId: number;
  collateralId: number;
  status: ProofReviewStatus;
  ownerName: string | null;
  locationText: string | null;
  appraisedValue: string;
  seniorClaimAmount: string | null;
  appraisalDate: string | null;
  confidence: string;
  matchNote: string | null;
  ocrModel: string;
  fileName: string;
  reviewedBy: number | null;
  createdAt: string;
}

export interface DepositProofItem {
  id: number;
  sellerId: number;
  referenceType: string;
  referenceId: string;
  status: ProofReviewStatus;
  senderName: string | null;
  transferDate: string | null;
  transferAmount: string;
  confidence: string;
  matchNote: string | null;
  ocrModel: string;
  fileName: string;
  reviewedBy: number | null;
  createdAt: string;
}

export interface ReviewPayload {
  matched: boolean;
  note: string;
}

const queueParams = (status: ProofReviewStatus, limit: number) => ({ params: { status, limit } });

export const reviewQueueApi = {
  listCardReceipts: async (status: ProofReviewStatus = 'NEEDS_REVIEW', limit = 50) =>
    (await api.get<CardReceiptItem[]>('/admin/expense-receipts', queueParams(status, limit))).data,

  reviewCardReceipt: async (id: number, payload: ReviewPayload) =>
    (await api.post<CardReceiptItem>(`/admin/expense-receipts/${id}/review`, payload)).data,

  listInsuranceDocuments: async (status: ProofReviewStatus = 'NEEDS_REVIEW', limit = 50) =>
    (await api.get<InsuranceDocumentItem[]>('/api/insurance/application-documents', queueParams(status, limit))).data,

  reviewInsuranceDocument: async (id: number, payload: ReviewPayload) =>
    (await api.post<InsuranceDocumentItem>(`/api/insurance/application-documents/${id}/review`, payload)).data,

  listLoanCollateralDocuments: async (status: ProofReviewStatus = 'NEEDS_REVIEW', limit = 50) =>
    (await api.get<LoanCollateralDocumentItem[]>('/loans/secured/collateral-documents', queueParams(status, limit))).data,

  reviewLoanCollateralDocument: async (id: number, payload: ReviewPayload) =>
    (await api.post<LoanCollateralDocumentItem>(`/loans/secured/collateral-documents/${id}/review`, payload)).data,

  listDepositProofs: async (status: ProofReviewStatus = 'NEEDS_REVIEW', limit = 50) =>
    (await api.get<DepositProofItem[]>('/admin/deposits/proofs', queueParams(status, limit))).data,

  reviewDepositProof: async (id: number, payload: ReviewPayload) =>
    (await api.post<DepositProofItem>(`/admin/deposits/proofs/${id}/review`, payload)).data,
};
