import { describe, it, expect, vi, beforeEach } from 'vitest';
import { marketApi, QUOTE_SOURCE_LABEL } from '@/api/market';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

describe('marketApi.latest', () => {
  beforeEach(() => vi.resetAllMocks());

  it('종목 최신 시세 스냅샷을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        stockCode: '005930',
        name: '삼성전자',
        market: 'KOSPI',
        latest: {
          baseDate: '2026-08-14',
          closePrice: 71500,
          openPrice: 71000,
          highPrice: 72000,
          lowPrice: 70800,
          priorDayDiff: 500,
          fluctuationRate: 0.7,
          volume: 12_000_000,
          tradeAmount: 858_000_000_000,
          listedShares: 5_969_782_550,
          marketCap: 426_839_452_325_000,
          source: 'EXCHANGE',
        },
      },
    });

    const result = await marketApi.latest('005930');

    expect(api.get).toHaveBeenCalledWith('/api/market/stocks/005930/latest');
    expect(result.latest?.source).toBe('EXCHANGE');
  });

  it('시세 미적재 종목은 latest=null 로 온다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { stockCode: '900000', name: '미적재', market: 'KONEX', latest: null },
    });

    const result = await marketApi.latest('900000');

    expect(result.latest).toBeNull();
  });

  it('없는 종목은 404 가 전파된다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 404 } });

    await expect(marketApi.latest('999999')).rejects.toMatchObject({ response: { status: 404 } });
  });

  it('출처 라벨은 도메인 값이 아니라 화면 문구로만 쓴다', () => {
    expect(QUOTE_SOURCE_LABEL.SAMPLE).toBe('근사 샘플');
    expect(QUOTE_SOURCE_LABEL.EXCHANGE).toBe('거래소 공시');
  });
});
