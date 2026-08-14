import api from './axios';

/**
 * 정산 원장 API — 조회는 `/api/ledger/**`(ADMIN·MANAGER), 기간·시산표·마감은
 * `/admin/ledger-periods/**`(ADMIN 전용). 두 표면의 권한 등급이 다르므로 화면도 나눠서 그린다.
 */

/** 분개 상태 — POSTED 는 수정 불가이며 정정은 역분개로만 한다. */
export type LedgerEntryStatus = 'PENDING' | 'POSTED' | 'REVERSED';

export interface LedgerEntry {
  id: number;
  referenceId: number;
  referenceType: string;
  entryType: string;
  debitAccount: string;
  creditAccount: string;
  amount: string;
  status: LedgerEntryStatus;
  settlementDate: string;
  postedAt: string | null;
  memo: string | null;
  createdAt: string;
}

/** 기간 상태 — CLOSED 면 그 기간에는 더 이상 분개가 붙지 않는다. */
export interface LedgerPeriod {
  id: number;
  periodYm: string;
  status: string;
  closedAt: string | null;
  closedBy: string | null;
  totalDebit: string;
  totalCredit: string;
  createdAt: string;
}

export interface TrialBalanceLine {
  account: string;
  debit: string;
  credit: string;
  net: string;
}

export interface TrialBalance {
  periodYm: string;
  lines: TrialBalanceLine[];
  totalDebit: string;
  totalCredit: string;
  /** 차변 합 = 대변 합. false 면 원장 불변식이 깨진 것이라 즉시 조사 대상이다. */
  balanced: boolean;
}

export const ledgerApi = {
  /** 기간별 분개 목록 (보고·감사) */
  entries: async (from: string, to: string): Promise<LedgerEntry[]> =>
    (await api.get<LedgerEntry[]>('/api/ledger/entries', { params: { from, to } })).data,

  /** 정산 1건의 분개 — 계산 근거 추적 */
  bySettlement: async (settlementId: number): Promise<LedgerEntry[]> =>
    (await api.get<LedgerEntry[]>(`/api/ledger/settlements/${settlementId}`)).data,

  /** 환불 1건의 역분개 */
  byRefund: async (refundId: number): Promise<LedgerEntry[]> =>
    (await api.get<LedgerEntry[]>(`/api/ledger/refunds/${refundId}`)).data,

  /** 기간 상태 (ADMIN) */
  period: async (periodYm: string): Promise<LedgerPeriod> =>
    (await api.get<LedgerPeriod>(`/admin/ledger-periods/${periodYm}`)).data,

  /** 시산표 (ADMIN) */
  trialBalance: async (periodYm: string): Promise<TrialBalance> =>
    (await api.get<TrialBalance>(`/admin/ledger-periods/${periodYm}/trial-balance`)).data,

  /** 기간 마감 (ADMIN) — 되돌릴 수 없다 */
  close: async (periodYm: string): Promise<LedgerPeriod> =>
    (await api.post<LedgerPeriod>(`/admin/ledger-periods/${periodYm}/close`)).data,
};
