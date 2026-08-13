import api from './axios';

/**
 * 정보계 월마감 API — `/admin/monthly-closing/**` (**ADMIN 전용**).
 *
 * <p>대상 월의 DONE 정산을 셀러별로 집계해 마트에 적재한다. 재실행은 기간 단위 교체라 멱등이지만,
 * 원장이 마감된 기간에 COMPLETED 마트가 있으면 409 로 막힌다 — 확정된 장부 위에 새 집계를
 * 덮어쓰지 못하게 하는 안전장치다.
 */

export type ClosingStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface MonthlyClosingRun {
  periodYm: string;
  status: ClosingStatus;
  triggeredBy: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  sellerCount: number;
  settlementCount: number;
  /** 셀러 매핑이 안 된 정산 건수 — 집계에서 빠졌다는 뜻이라 0 이 아니면 확인이 필요하다 */
  unmappedCount: number;
  /** 아직 DONE 이 아닌 정산 건수 — 이 달 집계에 포함되지 않았다 */
  pendingCount: number;
  totalGross: string | null;
  totalRefunded: string | null;
  totalCommission: string | null;
  totalHoldback: string | null;
  totalNet: string | null;
  failureReason: string | null;
}

export interface SellerMonthlyClosing {
  sellerId: number;
  settlementCount: number;
  grossAmount: string;
  refundedAmount: string;
  commissionAmount: string;
  holdbackAmount: string;
  netAmount: string;
}

export interface MonthlyClosing {
  run: MonthlyClosingRun;
  sellers: SellerMonthlyClosing[];
}

export const monthlyClosingApi = {
  /** 마감 조회 — 이력이 없으면 404 */
  get: async (periodYm: string): Promise<MonthlyClosing> =>
    (await api.get<MonthlyClosing>(`/admin/monthly-closing/${periodYm}`)).data,

  /** 마감 실행 — 재실행은 기간 마트 전체 교체(멱등) */
  run: async (periodYm: string): Promise<MonthlyClosingRun> =>
    (await api.post<MonthlyClosingRun>(`/admin/monthly-closing/${periodYm}/run`)).data,
};
