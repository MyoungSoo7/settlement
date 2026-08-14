import api from './axios';
import { newIdempotencyKey } from './payout';

/**
 * 카드사 분쟁(차지백) 콘솔 API — `/admin/chargebacks/**` (**ADMIN 전용**).
 *
 * <p>수락(accept)은 셀러에게서 돈을 걷는 결정이다 — settlement_adjustments 에 음수 행이
 * 생기고 정산금이 깎인다. 그래서 accept/reject 는 `Idempotency-Key` 를 항상 실어 보낸다.
 * 네트워크 재시도나 더블클릭으로 같은 분쟁이 두 번 차감되면 되돌리기 어렵기 때문이다
 * (서버는 키가 없으면 멱등을 적용하지 않으므로, 붙이는 책임이 클라이언트에 있다).
 */

export type ChargebackStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';

export type ChargebackReason =
  | 'FRAUD' | 'DUPLICATE' | 'NOT_RECEIVED' | 'PRODUCT_NOT_AS_DESCRIBED' | 'OTHER';

export type ChargebackSource = 'MANUAL' | 'PG_WEBHOOK';

export const CHARGEBACK_REASON_LABEL: Record<ChargebackReason, string> = {
  FRAUD: '도용·사기',
  DUPLICATE: '중복 결제',
  NOT_RECEIVED: '미수령',
  PRODUCT_NOT_AS_DESCRIBED: '상품 상이',
  OTHER: '기타',
};

export const CHARGEBACK_STATUS_LABEL: Record<ChargebackStatus, string> = {
  OPEN: '미결',
  ACCEPTED: '수락(셀러 부담)',
  REJECTED: '기각(정산 영향 없음)',
};

export interface Chargeback {
  id: number;
  paymentId: number | null;
  settlementId: number | null;
  amount: string;
  reasonCode: ChargebackReason;
  reasonDetail: string | null;
  status: ChargebackStatus;
  source: ChargebackSource;
  pgChargebackId: string | null;
  decidedBy: string | null;
  decisionNote: string | null;
  raisedAt: string | null;
  decidedAt: string | null;
}

export interface OpenChargebackRequest {
  paymentId: number;
  settlementId: number;
  amount: string;
  reasonCode: ChargebackReason;
  reasonDetail?: string;
}

interface Envelope { chargeback: Chargeback }

export const chargebackApi = {
  /** 상태별 목록 */
  list: async (status: ChargebackStatus, max = 20): Promise<Chargeback[]> =>
    (await api.get<Envelope[]>('/admin/chargebacks', { params: { status, max } }))
      .data.map((e) => e.chargeback),

  /** 상세 */
  get: async (id: number): Promise<Chargeback> =>
    (await api.get<Envelope>(`/admin/chargebacks/${id}`)).data.chargeback,

  /** 수동 등록 (PG 통지 누락분) */
  open: async (body: OpenChargebackRequest): Promise<Chargeback> =>
    (await api.post<Envelope>('/admin/chargebacks', body)).data.chargeback,

  /** 셀러 책임 인정 — 정산금에서 차감된다. 중복 차감 방지를 위해 멱등키 필수. */
  accept: async (id: number, note: string): Promise<Chargeback> =>
    (await api.post<Envelope>(`/admin/chargebacks/${id}/accept`, { note }, {
      headers: { 'Idempotency-Key': newIdempotencyKey() },
    })).data.chargeback,

  /** 셀러 증빙 인정 — 분쟁 종결, 정산 영향 없음. 사유 필수. */
  reject: async (id: number, note: string): Promise<Chargeback> =>
    (await api.post<Envelope>(`/admin/chargebacks/${id}/reject`, { note }, {
      headers: { 'Idempotency-Key': newIdempotencyKey() },
    })).data.chargeback,
};
