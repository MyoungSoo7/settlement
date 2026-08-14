import api from './axios';

/**
 * 정산금 출금(payout) 운영 콘솔 — settlement-service {@code PayoutAdminController}
 * ({@code /admin/payouts/**}, SecurityConfig 가 ADMIN 으로 게이트).
 *
 * <p><b>되돌리기 어려운 외부 송금</b>을 다루는 표면이다. 그래서 두 가지가 규약이다:
 * <ul>
 *   <li>상태를 바꾸는 호출(retry·cancel·bounce)은 {@code Idempotency-Key} 를 반드시 실어 보낸다.
 *       키가 없으면 서버가 멱등을 적용하지 않아, 더블클릭 한 번이 두 번의 재시도가 된다.
 *       중복 키는 409 로 되돌아온다.
 *   <li>미리보기(preview)는 아무것도 바꾸지 않는다 — 실행 전에 규모를 먼저 본다.
 * </ul>
 *
 * <p>목록·상세 응답이 {@code { payout: {...} }} 로 감싸여 오므로 여기서 벗겨 돌려준다.
 */

export type PayoutStatus = 'REQUESTED' | 'SENDING' | 'COMPLETED' | 'FAILED' | 'CANCELED';

export const PAYOUT_STATUS_LABEL: Record<PayoutStatus, string> = {
  REQUESTED: '지급 대기',
  SENDING: '송금 중',
  COMPLETED: '지급 완료',
  FAILED: '지급 실패',
  CANCELED: '지급 취소',
};

/** 계좌번호는 서버가 마스킹해서 준다 — 원문은 응답에 실리지 않는다. */
export interface Payout {
  id: number;
  settlementId: number;
  sellerId: number;
  amount: number;
  status: PayoutStatus;
  bank: string | null;
  account: string | null;
  holder: string | null;
  firmBankingTxnId: string | null;
  failureReason: string | null;
  retryCount: number;
  operatorId: string | null;
  requestedAt: string | null;
  sentAt: string | null;
  completedAt: string | null;
  failedAt: string | null;
}

/** 미리보기 1건 — 밀린 건은 reason 에 사유가 담긴다. */
export interface PayoutPreviewLine {
  payoutId: number;
  sellerId: number;
  amount: number;
  sendable: boolean;
  reason: string | null;
}

export interface PayoutPreview {
  sendableCount: number;
  sendableAmount: number;
  limitedCount: number;
  limitedAmount: number;
  lines: PayoutPreviewLine[];
}

export interface PayoutBounceResult {
  bouncedPayoutId: number;
  reason: string | null;
  reissuedPayoutId: number | null;
  reissued?: Payout;
}

export interface PayoutExecuteReport {
  succeeded: number;
  failed: number;
  limitedSkipped: number;
}

interface PayoutEnvelope {
  payout: Payout;
}

/**
 * 멱등 키를 만든다. 화면 한 번의 조작 = 키 하나여야 하므로 호출 시점에 생성한다.
 * {@code crypto.randomUUID} 가 없는 구형 환경에서도 충돌 확률이 실무상 무시할 수준이면 된다.
 */
export const newIdempotencyKey = (): string =>
  typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `payout-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

const idempotent = (key: string) => ({ headers: { 'Idempotency-Key': key } });

export const payoutApi = {
  /** GET /admin/payouts/failed — 재시도·취소 판단 대상. */
  listFailed: async (limit = 20): Promise<Payout[]> => {
    const response = await api.get<PayoutEnvelope[]>('/admin/payouts/failed', { params: { limit } });
    return response.data.map((e) => e.payout);
  },

  /** GET /admin/payouts/pending — 다음 배치가 집어갈 REQUESTED 건. */
  listPending: async (limit = 20): Promise<Payout[]> => {
    const response = await api.get<PayoutEnvelope[]>('/admin/payouts/pending', { params: { limit } });
    return response.data.map((e) => e.payout);
  },

  /** GET /admin/payouts/{id} */
  get: async (id: number): Promise<Payout> => {
    const response = await api.get<PayoutEnvelope>(`/admin/payouts/${id}`);
    return response.data.payout;
  },

  /** POST /admin/payouts/{id}/retry — FAILED → REQUESTED 복원. */
  retry: async (id: number, idempotencyKey: string): Promise<Payout> => {
    const response = await api.post<PayoutEnvelope>(
      `/admin/payouts/${id}/retry`, null, idempotent(idempotencyKey));
    return response.data.payout;
  },

  /** POST /admin/payouts/{id}/cancel — 영구 취소. 사유는 감사 추적에 남는다. */
  cancel: async (id: number, reason: string, idempotencyKey: string): Promise<Payout> => {
    const response = await api.post<PayoutEnvelope>(
      `/admin/payouts/${id}/cancel`, { reason }, idempotent(idempotencyKey));
    return response.data.payout;
  },

  /**
   * POST /admin/payouts/{id}/bounce — 은행 반송 기록 + 정정계좌 재지급.
   * 계좌 정정(/admin/seller-bank-accounts)이 <b>선행</b>이다. 안 고치고 반송을 기록하면
   * 같은 잘못된 계좌로 재지급된다.
   */
  bounce: async (id: number, reason: string, idempotencyKey: string): Promise<PayoutBounceResult> => {
    const response = await api.post<PayoutBounceResult>(
      `/admin/payouts/${id}/bounce`, { reason }, idempotent(idempotencyKey));
    return response.data;
  },

  /** GET /admin/payouts/preview — 상태를 바꾸지 않는다. 실행 전 규모 확인용. */
  preview: async (): Promise<PayoutPreview> => {
    const response = await api.get<PayoutPreview>('/admin/payouts/preview');
    return response.data;
  },

  /** POST /admin/payouts/execute-now — 실자금 이동. 감사 기록이 서버에서 남는다. */
  executeNow: async (): Promise<PayoutExecuteReport> => {
    const response = await api.post<PayoutExecuteReport>('/admin/payouts/execute-now');
    return response.data;
  },
};
