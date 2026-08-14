import api from './axios';

/**
 * 지급후 회수 채권·상계 조회 API — `GET /admin/recoveries?sellerId=` (ADMIN·MANAGER, 읽기 전용).
 *
 * <p>이미 지급이 나간 뒤에 환불·차지백·PG대사로 마이너스 조정이 생기면, 그 돈은 다음 정산에서
 * 상계(offset)해 걷는다. 이 API 는 셀러 한 명의 <b>미상계 잔액</b>과 그 근거(채권 목록·상계 이력)를
 * 한 번에 준다. 쓰기 경로는 없다 — 상계는 정산 파이프라인이 자동으로 집행한다.
 */

/** OPEN 만 잔액에 잡힌다. CLOSED 는 전액 상계돼 닫힌 채권이다. */
export type RecoveryStatus = 'OPEN' | 'CLOSED';

export interface SellerRecovery {
  id: number;
  /** 이 채권을 만든 마이너스 조정(settlement_adjustments) */
  sourceAdjustmentId: number | null;
  originalAmount: string;
  allocatedAmount: string;
  /** originalAmount - allocatedAmount — 아직 못 걷은 금액 */
  outstanding: string;
  status: RecoveryStatus;
  createdAt: string | null;
  closedAt: string | null;
}

/** 채권이 어느 정산에서 얼마씩 상계됐는지 — 회수 근거 추적 */
export interface RecoveryAllocation {
  id: number;
  recoveryId: number;
  settlementId: number | null;
  amount: string;
  createdAt: string | null;
}

export interface SellerRecoverySummary {
  sellerId: number;
  /** OPEN 채권의 outstanding 합 — 이 셀러에게서 앞으로 걷을 총액 */
  outstandingTotal: string;
  recoveries: SellerRecovery[];
  allocations: RecoveryAllocation[];
}

export const recoveryApi = {
  bySeller: async (sellerId: number): Promise<SellerRecoverySummary> =>
    (await api.get<SellerRecoverySummary>('/admin/recoveries', { params: { sellerId } })).data,
};
