import api from './axios';

/** 종목 일별 시세 1건 (market-service — KRX 상장사 시세·시가총액 조회) */
export interface MarketQuote {
  baseDate: string;             // ISO yyyy-MM-dd
  closePrice: number;
  openPrice: number | null;
  highPrice: number | null;
  lowPrice: number | null;
  priorDayDiff: number | null;  // 전일 대비
  fluctuationRate: number | null; // 등락률 %
  volume: number | null;        // 거래량
  tradeAmount: number | null;   // 거래대금(원)
  listedShares: number | null;  // 상장주식수
  marketCap: number | null;     // 시가총액(원)
  source: string;               // SEED(근사 샘플) / KRX(실데이터)
}

/** 종목 + 최신 시세 스냅샷 — 시세 미적재 시 latest=null */
export interface StockSnapshot {
  stockCode: string;
  name: string;
  market: string;               // KOSPI / KOSDAQ / KONEX
  latest: MarketQuote | null;
}

export const marketApi = {
  /** 단일 종목 최신 시세. GET /api/market/stocks/{stockCode}/latest */
  latest: async (stockCode: string): Promise<StockSnapshot> => {
    const res = await api.get<StockSnapshot>(`/api/market/stocks/${stockCode}/latest`);
    return res.data;
  },
};
