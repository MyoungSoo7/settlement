import api from './axios';

/** 지표 최신 관측치 */
export interface IndicatorObservation {
  observedDate: string; // ISO yyyy-MM-dd
  value: number;
}

/** 전기 대비 변동 */
export interface IndicatorChange {
  amount: number;
  ratePercent: number | null;
}

/** 경제지표 (기준금리·국고채3년·USD/KRW·CPI) */
export interface EconomicIndicator {
  code: string;
  name: string;
  unit: string;
  cycle: string; // D 일별 / M 월별
  latest: IndicatorObservation | null;
  change: IndicatorChange | null;
}

/** 시계열 관측치 1건 */
export interface IndicatorSeriesPoint {
  observedDate: string;
  value: number;
  source: string; // SEED(근사 샘플) / ECOS(실데이터)
}

/** 지표 시계열 */
export interface IndicatorSeries {
  code: string;
  name: string;
  unit: string;
  points: IndicatorSeriesPoint[];
}

export const economicsApi = {
  /** 지표 카탈로그 전체 + 최신값/변동. GET /api/economics/indicators */
  indicators: async (): Promise<EconomicIndicator[]> => {
    const res = await api.get<EconomicIndicator[]>('/api/economics/indicators');
    return res.data;
  },

  /** 지표 시계열. GET /api/economics/indicators/{code}/series?from=&to= */
  series: async (code: string, from?: string, to?: string): Promise<IndicatorSeries> => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const qs = params.toString();
    const res = await api.get<IndicatorSeries>(
      `/api/economics/indicators/${code}/series${qs ? `?${qs}` : ''}`
    );
    return res.data;
  },
};

export const fetchIndicators = economicsApi.indicators;
export const fetchIndicatorSeries = economicsApi.series;
