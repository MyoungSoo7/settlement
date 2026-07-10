import { describe, it, expect, vi, beforeEach } from 'vitest';
import { investmentApi, type InvestmentFunding, type InvestmentOrder, type InvestmentScore } from '@/api/investment';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const mockScore: InvestmentScore = {
  stockCode: '005930',
  companyName: '삼성전자',
  market: 'KOSPI',
  fiscalYear: 2026,
  totalScore: 82,
  grade: 'AA',
  investable: true,
  profitability: { score: 30, maxScore: 40, operatingMargin: 12.5, roa: 8.1 },
  stability: { score: 28, maxScore: 30, debtRatio: 45.2, equityRatio: 68.8 },
  growth: { score: 24, maxScore: 30, revenueGrowth: 9.4, netIncomeGrowth: null },
};

const mockOrder: InvestmentOrder = {
  id: 7,
  sellerId: 1,
  stockCode: '005930',
  amount: 1_000_000,
  scoreAtOrder: 82,
  gradeAtOrder: 'AA',
  status: 'REQUESTED',
};

const mockFunding: InvestmentFunding = {
  sellerId: 1,
  confirmedTotal: 50_000_000,
  investedTotal: 12_000_000,
  available: 38_000_000,
};

describe('investmentApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('score', () => {
    it('종목코드로 투자 점수를 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockScore });

      const result = await investmentApi.score('005930');

      expect(api.get).toHaveBeenCalledWith('/api/investment/scores/005930');
      expect(result).toEqual(mockScore);
      expect(result.grade).toBe('AA');
    });
  });

  describe('funding', () => {
    it('셀러 ID로 투자 재원을 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockFunding });

      const result = await investmentApi.funding(1);

      expect(api.get).toHaveBeenCalledWith('/api/investment/funding/1');
      expect(result.available).toBe(38_000_000);
    });
  });

  describe('createOrder', () => {
    it('요청 바디로 투자 주문을 생성한다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockOrder });

      const result = await investmentApi.createOrder({ sellerId: 1, stockCode: '005930', amount: 1_000_000 });

      expect(api.post).toHaveBeenCalledWith('/api/investment/orders', { sellerId: 1, stockCode: '005930', amount: 1_000_000 });
      expect(result.status).toBe('REQUESTED');
    });

    it('투자부적격/재원부족 시 422 에러를 throw한다', async () => {
      const error = { response: { status: 422, data: { message: '재원 부족' } } };
      vi.mocked(api.post).mockRejectedValueOnce(error);

      await expect(
        investmentApi.createOrder({ sellerId: 1, stockCode: '005930', amount: 999_999_999 })
      ).rejects.toMatchObject({ response: { status: 422 } });
    });
  });

  describe('execute / cancel', () => {
    it('주문을 집행한다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: { ...mockOrder, status: 'EXECUTED' } });

      const result = await investmentApi.execute(7);

      expect(api.post).toHaveBeenCalledWith('/api/investment/orders/7/execute');
      expect(result.status).toBe('EXECUTED');
    });

    it('주문을 취소한다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: { ...mockOrder, status: 'CANCELED' } });

      const result = await investmentApi.cancel(7);

      expect(api.post).toHaveBeenCalledWith('/api/investment/orders/7/cancel');
      expect(result.status).toBe('CANCELED');
    });
  });

  describe('ordersBySeller', () => {
    it('셀러별 주문 목록을 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: [mockOrder] });

      const result = await investmentApi.ordersBySeller(1);

      expect(api.get).toHaveBeenCalledWith('/api/investment/orders?sellerId=1');
      expect(result).toHaveLength(1);
    });
  });
});
