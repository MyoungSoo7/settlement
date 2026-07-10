import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  accountApi,
  type AccountLoanAggregate,
  type OwnerAccounts,
  type TrialBalance,
} from '@/api/account';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const mockLoanAggregate: AccountLoanAggregate = {
  disbursedTotal: 100_000_000,
  repaidTotal: 40_000_000,
  outstanding: 60_000_000,
  corporateDisbursedTotal: 30_000_000,
  corporateOutstanding: 25_000_000,
  entryCount: 12,
};

const mockTrialBalance: TrialBalance = {
  accounts: [
    { account: 'CASH', debitTotal: 100_000, creditTotal: 0 },
    { account: 'LOAN_RECEIVABLE', debitTotal: 0, creditTotal: 100_000 },
  ],
  totalDebit: 100_000,
  totalCredit: 100_000,
  balanced: true,
};

const mockOwnerAccounts: OwnerAccounts = {
  ownerType: 'SELLER',
  ownerId: 1,
  balances: [
    { account: 'CASH', side: 'DEBIT', balance: 50_000 },
    { account: 'PAYABLE', side: 'CREDIT', balance: 20_000 },
  ],
  entryCount: 4,
};

describe('accountApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('aggregates', () => {
    it('대출 집계를 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockLoanAggregate });

      const result = await accountApi.loanAggregate();

      expect(api.get).toHaveBeenCalledWith('/api/account/aggregates/loans');
      expect(result.corporateOutstanding).toBe(25_000_000);
    });

    it('투자 집계를 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: { investedTotal: 12_000_000, orderCount: 3 } });

      const result = await accountApi.investmentAggregate();

      expect(api.get).toHaveBeenCalledWith('/api/account/aggregates/investments');
      expect(result.orderCount).toBe(3);
    });

    it('정산 집계를 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: { scheduledTotal: 5_000_000, confirmedTotal: 3_000_000, pendingScheduled: 2_000_000 } });

      const result = await accountApi.settlementAggregate();

      expect(api.get).toHaveBeenCalledWith('/api/account/aggregates/settlements');
      expect(result.pendingScheduled).toBe(2_000_000);
    });
  });

  describe('trialBalance', () => {
    it('시산표를 조회하고 차·대 균형 플래그를 반환한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockTrialBalance });

      const result = await accountApi.trialBalance();

      expect(api.get).toHaveBeenCalledWith('/api/account/trial-balance');
      expect(result.balanced).toBe(true);
      expect(result.totalDebit).toBe(result.totalCredit);
    });
  });

  describe('ownerAccounts', () => {
    it('소유자 유형·ID로 계정 잔액을 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockOwnerAccounts });

      const result = await accountApi.ownerAccounts('SELLER', 1);

      expect(api.get).toHaveBeenCalledWith('/api/account/accounts/SELLER/1');
      expect(result.balances).toHaveLength(2);
    });

    it('법인 계정도 조회할 수 있다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: { ...mockOwnerAccounts, ownerType: 'CORPORATE', ownerId: 9 } });

      await accountApi.ownerAccounts('CORPORATE', 9);

      expect(api.get).toHaveBeenCalledWith('/api/account/accounts/CORPORATE/9');
    });

    it('API 오류 시 에러를 throw한다', async () => {
      const error = { response: { status: 404 } };
      vi.mocked(api.get).mockRejectedValueOnce(error);

      await expect(accountApi.ownerAccounts('SELLER', 9999)).rejects.toMatchObject({ response: { status: 404 } });
    });
  });
});
