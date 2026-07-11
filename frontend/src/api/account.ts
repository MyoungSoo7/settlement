import api from './axios';

/** 대출 집계 — GET /api/account/aggregates/loans */
export interface AccountLoanAggregate {
  disbursedTotal: number;
  repaidTotal: number;
  outstanding: number;
  corporateDisbursedTotal: number;
  corporateOutstanding: number;
  entryCount: number;
}

/** 투자 집계 — GET /api/account/aggregates/investments */
export interface AccountInvestmentAggregate {
  investedTotal: number;
  orderCount: number;
}

/** 정산 집계 — GET /api/account/aggregates/settlements */
export interface AccountSettlementAggregate {
  scheduledTotal: number;
  confirmedTotal: number;
  pendingScheduled: number;
}

/** 시산표 계정 행 */
export interface TrialBalanceAccount {
  account: string;
  debitTotal: number;
  creditTotal: number;
}

/** 시산표 — GET /api/account/trial-balance */
export interface TrialBalance {
  accounts: TrialBalanceAccount[];
  totalDebit: number;
  totalCredit: number;
  balanced: boolean;
}

export type OwnerType = 'SELLER' | 'CORPORATE';

/** 계정 잔액 (차/대) */
export interface AccountBalance {
  account: string;
  side: 'DEBIT' | 'CREDIT';
  balance: number;
}

/** owner 계정 잔액 — GET /api/account/accounts/{ownerType}/{ownerId} */
export interface OwnerAccounts {
  ownerType: OwnerType;
  ownerId: number;
  balances: AccountBalance[];
  entryCount: number;
}

export const accountApi = {
  /** 대출 집계. GET /api/account/aggregates/loans */
  loanAggregate: async (): Promise<AccountLoanAggregate> => {
    const res = await api.get<AccountLoanAggregate>('/api/account/aggregates/loans');
    return res.data;
  },

  /** 투자 집계. GET /api/account/aggregates/investments */
  investmentAggregate: async (): Promise<AccountInvestmentAggregate> => {
    const res = await api.get<AccountInvestmentAggregate>('/api/account/aggregates/investments');
    return res.data;
  },

  /** 정산 집계. GET /api/account/aggregates/settlements */
  settlementAggregate: async (): Promise<AccountSettlementAggregate> => {
    const res = await api.get<AccountSettlementAggregate>('/api/account/aggregates/settlements');
    return res.data;
  },

  /** 시산표. GET /api/account/trial-balance */
  trialBalance: async (): Promise<TrialBalance> => {
    const res = await api.get<TrialBalance>('/api/account/trial-balance');
    return res.data;
  },

  /** owner 계정 잔액. GET /api/account/accounts/{ownerType}/{ownerId} */
  ownerAccounts: async (ownerType: OwnerType, ownerId: number): Promise<OwnerAccounts> => {
    const res = await api.get<OwnerAccounts>(`/api/account/accounts/${ownerType}/${ownerId}`);
    return res.data;
  },
};
