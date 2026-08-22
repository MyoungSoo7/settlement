import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  pensionApi,
  savingsApi,
  timeDepositApi,
  type InstallmentSavings,
  type RetirementPension,
  type TimeDeposit,
} from '@/api/banking';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const deposit: TimeDeposit = {
  id: 1,
  depositorId: 'user-1',
  productName: '정기예금 12개월',
  principal: 10_000_000,
  annualRate: 3.5,
  earlyTerminationRate: 0.8,
  compounding: 'MONTHLY_COMPOUND',
  termMonths: 12,
  openedOn: '2026-01-01',
  maturityDate: '2027-01-01',
  status: 'ACTIVE',
  closedOn: null,
  settledInterest: null,
  payoutAmount: null,
};

const savings = { id: 2, depositorId: 'user-1' } as unknown as InstallmentSavings;
const pension = { id: 3, subscriberId: 'user-1' } as unknown as RetirementPension;

describe('bankingApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('정기예금', () => {
    it('목록 조회에 가입자 식별자를 싣지 않는다', async () => {
      // 서버가 JWT 주체에서 파생한다. 클라이언트가 id 를 실어 보내면 그게 곧 IDOR 통로다.
      vi.mocked(api.get).mockResolvedValueOnce({ data: [deposit] });

      const result = await timeDepositApi.listMine();

      expect(api.get).toHaveBeenCalledWith('/api/banking/time-deposits');
      expect(result).toHaveLength(1);
    });

    it('개설은 입력을 본문으로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: deposit });

      const input = {
        productName: '정기예금 12개월',
        principal: 10_000_000,
        annualRate: 3.5,
        earlyTerminationRate: 0.8,
        compounding: 'MONTHLY_COMPOUND' as const,
        termMonths: 12,
      };
      await timeDepositApi.open(input);

      expect(api.post).toHaveBeenCalledWith('/api/banking/time-deposits', input);
    });

    it('만기 해지와 중도 해지는 서로 다른 엔드포인트다', async () => {
      // 중도해지는 약정 이율이 아니라 더 낮은 중도해지 이율로 정산된다. 두 경로를
      // 하나로 합치면 화면이 이자를 과대 표시하게 된다.
      vi.mocked(api.post).mockResolvedValue({ data: { ...deposit, status: 'CLOSED' } });

      await timeDepositApi.closeOnMaturity(1);
      await timeDepositApi.closeEarly(1);

      expect(api.post).toHaveBeenNthCalledWith(1, '/api/banking/time-deposits/1/close');
      expect(api.post).toHaveBeenNthCalledWith(2, '/api/banking/time-deposits/1/close-early');
    });
  });

  describe('적금', () => {
    it('목록과 개설', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: [savings] });
      vi.mocked(api.post).mockResolvedValueOnce({ data: savings });

      await savingsApi.listMine();
      await savingsApi.open({
        productName: '자유적금',
        savingsType: 'FLEXIBLE',
        monthlyAmount: 300_000,
        annualRate: 3.0,
        earlyTerminationRate: 0.5,
        compounding: 'SIMPLE',
        termMonths: 24,
      } as never);

      expect(api.get).toHaveBeenCalledWith('/api/banking/savings');
      expect(api.post).toHaveBeenCalledWith('/api/banking/savings', expect.objectContaining({
        productName: '자유적금',
      }));
    });

    it('회차 납입은 회차 번호를 함께 보낸다', async () => {
      // 회차가 빠지면 서버가 같은 회차 중복 납입을 막을 근거를 잃는다.
      vi.mocked(api.post).mockResolvedValueOnce({ data: savings });

      await savingsApi.pay(2, 3, 300_000);

      expect(api.post).toHaveBeenCalledWith('/api/banking/savings/2/installments', {
        round: 3,
        amount: 300_000,
      });
    });

    it('만기·중도 해지 경로가 분리돼 있다', async () => {
      vi.mocked(api.post).mockResolvedValue({ data: savings });

      await savingsApi.closeOnMaturity(2);
      await savingsApi.closeEarly(2);

      expect(api.post).toHaveBeenNthCalledWith(1, '/api/banking/savings/2/close/maturity');
      expect(api.post).toHaveBeenNthCalledWith(2, '/api/banking/savings/2/close/early');
    });
  });

  describe('퇴직연금', () => {
    it('목록·개설·부담금 납입', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: [pension] });
      vi.mocked(api.post).mockResolvedValue({ data: pension });

      await pensionApi.listMine();
      await pensionApi.open({
        scheme: 'DC',
        employerName: '르뮤엘',
        birthDate: '1990-01-01',
        annualRate: 2.5,
      } as never);
      await pensionApi.contribute(3, 1_000_000, 'EMPLOYER' as never);

      expect(api.get).toHaveBeenCalledWith('/api/banking/pensions');
      expect(api.post).toHaveBeenNthCalledWith(1, '/api/banking/pensions', expect.anything());
      expect(api.post).toHaveBeenNthCalledWith(2, '/api/banking/pensions/3/contributions', {
        amount: 1_000_000,
        source: 'EMPLOYER',
      });
    });

    it('운용수익 인식은 본문을 보내지 않는다 — 금액도 발생일도 서버가 정한다', async () => {
      // 클라이언트가 금액을 실어 보낼 수 있으면 그게 곧 임의 증액이다(ADMIN·MANAGER 전용 경로).
      vi.mocked(api.post).mockResolvedValueOnce({ data: pension });

      await pensionApi.settleInterest(3);

      expect(api.post).toHaveBeenCalledWith('/api/banking/pensions/3/interest-settlements');
    });

    it('수급 개시와 수급 지급은 다른 엔드포인트다', async () => {
      vi.mocked(api.post).mockResolvedValue({ data: pension });

      await pensionApi.startBenefit(3, 'ANNUITY' as never);
      await pensionApi.payBenefit(3, 500_000);

      expect(api.post).toHaveBeenNthCalledWith(1, '/api/banking/pensions/3/benefit', {
        benefitType: 'ANNUITY',
      });
      expect(api.post).toHaveBeenNthCalledWith(2, '/api/banking/pensions/3/benefit-payments', {
        amount: 500_000,
      });
    });

    it('중도인출은 사유를 함께 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: pension });

      await pensionApi.withdrawMidway(3, 2_000_000, 'HOUSING' as never);

      expect(api.post).toHaveBeenCalledWith('/api/banking/pensions/3/mid-withdrawals', {
        amount: 2_000_000,
        reason: 'HOUSING',
      });
    });

    it('운용지시 변경은 PUT 이다 — 반복 호출이 이력을 쌓지 않는다', async () => {
      vi.mocked(api.put).mockResolvedValueOnce({ data: pension });

      await pensionApi.changeInvestmentInstruction(3, '채권형', 2.1);

      expect(api.put).toHaveBeenCalledWith('/api/banking/pensions/3/investment-instruction', {
        productName: '채권형',
        rate: 2.1,
      });
    });
  });
});
