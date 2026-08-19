import api from './axios';

/**
 * 매출 통계 API (settlement-service `report` 모듈).
 *
 * <p>화면이 알아야 할 것은 두 가지다.
 *
 * <ul>
 *   <li><b>증감률은 null 일 수 있다</b> — 직전 기간에 거래가 없었다는 뜻이며, 0% 가 아니다.
 *       0% 로 그리면 "변화 없음"으로 읽혀 정반대의 사실을 전한다. 화면은 "—"로 그린다.
 *   <li><b>sharePercent 는 이미 0~100 스케일</b>이다. 다시 100 을 곱하지 않는다.
 * </ul>
 *
 * <p>기간별 추이는 이 API 가 아니라 기존 {@code /api/reports/cashflow} 가 답한다 —
 * 같은 집계를 두 벌 두면 두 화면이 서로 다른 숫자를 말하게 된다.
 */

/** 매출을 가르는 축. 서버 enum(SalesDimension)과 1:1. */
export type SalesDimension =
  | 'PAYMENT_METHOD'
  | 'SELLER_TIER'
  | 'SETTLEMENT_STATUS'
  | 'SELLER'
  | 'PRODUCT';

export interface StatsPeriod {
  from: string;
  to: string;
  days: number;
}

export interface SalesTotals {
  transactionCount: number;
  gmv: number;
  refundedAmount: number;
  commissionAmount: number;
  netSettlement: number;
  refundRate: number;
}

/** 소수 4자리 비율(1.0 = +100%). 직전 기간이 0 이면 null. */
export interface SalesGrowth {
  gmv: number | null;
  netSettlement: number | null;
  transactionCount: number | null;
}

export interface SalesSummary {
  period: StatsPeriod;
  /** 비교 분모가 된 기간 — 화면은 "무엇과 비교했는지" 밝혀야 한다. */
  previousPeriod: StatsPeriod;
  current: SalesTotals;
  previous: SalesTotals;
  growth: SalesGrowth;
}

export interface SalesBreakdownRow {
  label: string;
  transactionCount: number;
  gmv: number;
  refundedAmount: number;
  commissionAmount: number;
  netSettlement: number;
  /** 0~100 스케일. 반올림 때문에 합이 정확히 100 이 아닐 수 있다. */
  sharePercent: number;
}

export interface SalesBreakdown {
  dimension: SalesDimension;
  totalTransactionCount: number;
  totalGmv: number;
  rows: SalesBreakdownRow[];
}

/** 기간 추이 — 기존 캐시플로우 리포트의 응답 중 화면이 쓰는 부분. */
export interface CashflowBucket {
  bucket: string;
  transactionCount: number;
  gmv: number;
  refundedAmount: number;
  commissionAmount: number;
  netSettlement: number;
}

export interface CashflowReport {
  period: { from: string; to: string; groupBy: string };
  totals: SalesTotals;
  buckets: CashflowBucket[];
}

export type BucketGranularity = 'day' | 'week' | 'month';

// 경로는 전체 리터럴로 적는다 — 조각을 이어 붙이면 사람 눈에도, 화면-API 대조 게이트에도
// 어떤 엔드포인트를 부르는지 보이지 않는다.
export const salesStatsApi = {
  summary: async (from: string, to: string) =>
    (await api.get<SalesSummary>('/api/reports/sales-stats/summary', {
      params: { from, to },
    })).data,

  breakdown: async (from: string, to: string, dimension: SalesDimension, limit = 10) =>
    (await api.get<SalesBreakdown>('/api/reports/sales-stats/breakdown', {
      params: { from, to, dimension, limit },
    })).data,

  /** 추이는 기존 리포트 API 를 그대로 쓴다. */
  cashflow: async (from: string, to: string, groupBy: BucketGranularity = 'day') =>
    (await api.get<CashflowReport>('/api/reports/cashflow', {
      params: { from, to, groupBy },
    })).data,
};
