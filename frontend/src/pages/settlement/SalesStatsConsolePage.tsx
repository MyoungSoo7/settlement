import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  salesStatsApi,
  type BucketGranularity,
  type CashflowReport,
  type SalesBreakdown,
  type SalesDimension,
  type SalesSummary,
} from '@/api/salesStats';
import { apiErrorMessage } from '@/lib/apiError';
import Spinner from '@/components/Spinner';

/**
 * 매출 통계 콘솔 (ADMIN·MANAGER).
 *
 * <p>정산 콘솔은 "장부가 맞는가"를 보는 화면들로 채워져 있었고, "얼마나 팔렸는가"를 보는 화면은
 * 없었다. 이 화면이 그 자리를 채운다 — 소스는 전부 settlement 자기 DB 다(프로젝션 포함).
 *
 * <p>화면이 지키는 규칙 셋:
 *
 * <ul>
 *   <li><b>증감률이 null 이면 "—"로 그린다.</b> 0% 로 그리면 "변화 없음"으로 읽히는데, 실제로는
 *       직전 기간에 거래가 없어 비교 자체가 불가능한 상태다.
 *   <li><b>비교 대상 기간을 항상 밝힌다.</b> 무엇과 비교한 수치인지 모르면 증감률은 근거가 없다.
 *   <li><b>구성비 합이 100 이 아닐 수 있음을 말한다.</b> 반올림의 결과이지 누락이 아니다.
 * </ul>
 *
 * <p>추이는 기존 {@code /api/reports/cashflow} 를 그대로 쓴다. 같은 집계를 새로 만들면 두 화면이
 * 서로 다른 매출을 말하게 된다.
 */

const DIMENSIONS: { value: SalesDimension; label: string; hint: string }[] = [
  { value: 'PAYMENT_METHOD', label: '결제수단', hint: '카드·계좌이체·가상계좌 등' },
  { value: 'SELLER_TIER', label: '셀러 등급', hint: '정산 시점 등급 — 수수료율과 직결' },
  { value: 'SETTLEMENT_STATUS', label: '정산 상태', hint: '요청·확정·지급 등 파이프라인 분포' },
  { value: 'SELLER', label: '셀러 랭킹', hint: '거래액 상위 셀러' },
  { value: 'PRODUCT', label: '상품 랭킹', hint: '거래액 상위 상품' },
];

const GRANULARITIES: { value: BucketGranularity; label: string }[] = [
  { value: 'day', label: '일별' },
  { value: 'week', label: '주별' },
  { value: 'month', label: '월별' },
];

const isoDate = (d: Date) => d.toISOString().slice(0, 10);

const daysAgo = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoDate(d);
};

const won = (v: number) => `${Math.round(v).toLocaleString()}원`;

/** 증감률 표기. null 은 비교 불가이므로 숫자가 아니라 "—"로 그린다. */
const growthText = (rate: number | null): { text: string; tone: string } => {
  if (rate === null || rate === undefined) {
    return { text: '—', tone: 'text-gray-400' };
  }
  const percent = (rate * 100).toFixed(1);
  if (rate > 0) return { text: `▲ ${percent}%`, tone: 'text-emerald-700' };
  if (rate < 0) return { text: `▼ ${percent.replace('-', '')}%`, tone: 'text-red-700' };
  return { text: '0.0%', tone: 'text-gray-500' };
};

const SalesStatsConsolePage: React.FC = () => {
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(daysAgo(0));
  const [dimension, setDimension] = useState<SalesDimension>('PAYMENT_METHOD');
  const [granularity, setGranularity] = useState<BucketGranularity>('day');
  const [limit, setLimit] = useState(10);

  const [summary, setSummary] = useState<SalesSummary | null>(null);
  const [breakdown, setBreakdown] = useState<SalesBreakdown | null>(null);
  const [cashflow, setCashflow] = useState<CashflowReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 셋을 함께 부른다 — 하나만 갱신되면 화면 안에서 기간이 어긋난 숫자가 공존한다.
      const [nextSummary, nextBreakdown, nextCashflow] = await Promise.all([
        salesStatsApi.summary(from, to),
        salesStatsApi.breakdown(from, to, dimension, limit),
        salesStatsApi.cashflow(from, to, granularity),
      ]);
      setSummary(nextSummary);
      setBreakdown(nextBreakdown);
      setCashflow(nextCashflow);
    } catch (err) {
      setError(apiErrorMessage(err, '매출 통계를 불러오지 못했습니다.'));
      setSummary(null);
      setBreakdown(null);
      setCashflow(null);
    } finally {
      setLoading(false);
    }
  }, [from, to, dimension, limit, granularity]);

  useEffect(() => { void load(); }, [load]);

  /** 막대 길이의 기준 — 최대 버킷 대비 비율. 0 이면 나누지 않는다. */
  const maxBucketGmv = useMemo(
    () => Math.max(1, ...(cashflow?.buckets ?? []).map((b) => b.gmv)),
    [cashflow],
  );

  const applyPreset = (days: number) => {
    setFrom(daysAgo(days - 1));
    setTo(daysAgo(0));
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">매출 통계</h1>
        <p className="text-sm text-gray-500 mt-1">
          정산 기준일로 집계한 거래액·수수료·순정산액과 그 구성입니다. 모든 수치는 settlement 자기
          DB(정산 원장 + 이벤트 프로젝션)에서 나오며, order DB 를 직접 읽지 않습니다.
        </p>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="stats-from" className="block text-xs font-medium text-gray-600 mb-1">시작일</label>
          <input id="stats-from" type="date" value={from} className="input w-40"
            onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label htmlFor="stats-to" className="block text-xs font-medium text-gray-600 mb-1">종료일</label>
          <input id="stats-to" type="date" value={to} className="input w-40"
            onChange={(e) => setTo(e.target.value)} />
        </div>
        <div>
          <label htmlFor="stats-dimension" className="block text-xs font-medium text-gray-600 mb-1">집계 축</label>
          <select id="stats-dimension" value={dimension} className="input w-44"
            onChange={(e) => setDimension(e.target.value as SalesDimension)}>
            {DIMENSIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="stats-limit" className="block text-xs font-medium text-gray-600 mb-1">상위 N</label>
          <input id="stats-limit" type="number" min={1} max={100} value={limit} className="input w-24"
            onChange={(e) => setLimit(Number(e.target.value) || 10)} />
        </div>
        <div>
          <label htmlFor="stats-granularity" className="block text-xs font-medium text-gray-600 mb-1">추이 단위</label>
          <select id="stats-granularity" value={granularity} className="input w-28"
            onChange={(e) => setGranularity(e.target.value as BucketGranularity)}>
            {GRANULARITIES.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
          </select>
        </div>
        <button onClick={() => void load()} disabled={loading}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
          조회
        </button>
        <div className="flex gap-1 ml-auto">
          {[7, 30, 90].map((d) => (
            <button key={d} onClick={() => applyPreset(d)}
              className="px-3 py-2 text-sm border border-gray-200 rounded-lg hover:bg-gray-50">
              최근 {d}일
            </button>
          ))}
        </div>
      </div>

      {error && <p role="alert" className="py-6 text-center text-red-600">{error}</p>}
      {loading && <div className="py-12 flex justify-center"><Spinner size="lg" message="집계하는 중..." /></div>}

      {summary && !loading && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-baseline gap-2">
            <h2 className="font-bold text-gray-900">기간 요약</h2>
            <span className="text-xs text-gray-500">
              {summary.period.from} ~ {summary.period.to} ({summary.period.days}일)
              {' · 비교 대상 '}
              {summary.previousPeriod.from} ~ {summary.previousPeriod.to}
            </span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
            {[
              { label: '거래액', value: won(summary.current.gmv), growth: summary.growth.gmv },
              { label: '순정산액', value: won(summary.current.netSettlement), growth: summary.growth.netSettlement },
              { label: '정산 건수', value: `${summary.current.transactionCount.toLocaleString()}건`, growth: summary.growth.transactionCount },
              { label: '수수료', value: won(summary.current.commissionAmount), growth: null },
              { label: '환불액', value: won(summary.current.refundedAmount), growth: null },
            ].map((card) => {
              const g = growthText(card.growth);
              return (
                <div key={card.label} className="bg-white rounded-xl border border-gray-200 p-4">
                  <p className="text-xs text-gray-500">{card.label}</p>
                  <p className="text-lg font-bold text-gray-900 mt-1">{card.value}</p>
                  <p className={`text-xs mt-1 ${g.tone}`}>
                    {card.growth === null ? '전기 대비 —' : `전기 대비 ${g.text}`}
                  </p>
                </div>
              );
            })}
          </div>

          {summary.growth.gmv === null && (
            <p className="text-xs text-gray-500">
              직전 기간에 거래가 없어 증감률을 계산할 수 없습니다 — 0% 가 아니라 <b>비교 불가</b>입니다.
            </p>
          )}
        </section>
      )}

      {cashflow && !loading && (
        <section className="space-y-2">
          <h2 className="font-bold text-gray-900">기간 추이</h2>
          {cashflow.buckets.length === 0 ? (
            <p className="py-8 text-center text-gray-400">이 기간에 집계된 정산이 없습니다.</p>
          ) : (
            <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-1">
              {cashflow.buckets.map((b) => (
                <div key={b.bucket} className="flex items-center gap-3 text-sm">
                  <span className="w-24 shrink-0 font-mono text-xs text-gray-500">{b.bucket}</span>
                  <span className="flex-1 h-4 bg-gray-100 rounded overflow-hidden">
                    <span className="block h-full bg-blue-500"
                      style={{ width: `${(b.gmv / maxBucketGmv) * 100}%` }} />
                  </span>
                  <span className="w-32 shrink-0 text-right text-gray-900">{won(b.gmv)}</span>
                  <span className="w-20 shrink-0 text-right text-gray-500">{b.transactionCount}건</span>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {breakdown && !loading && (
        <section className="space-y-2">
          <div className="flex flex-wrap items-baseline gap-2">
            <h2 className="font-bold text-gray-900">
              {DIMENSIONS.find((d) => d.value === breakdown.dimension)?.label ?? breakdown.dimension} 구성
            </h2>
            <span className="text-xs text-gray-500">
              {DIMENSIONS.find((d) => d.value === breakdown.dimension)?.hint}
            </span>
          </div>

          {breakdown.rows.length === 0 ? (
            <p className="py-8 text-center text-gray-400">이 기간에 집계된 정산이 없습니다.</p>
          ) : (
            <div className="bg-white rounded-xl border border-gray-200 overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 text-gray-600">
                  <tr>
                    <th className="text-left px-4 py-2 font-medium">구분</th>
                    <th className="text-right px-4 py-2 font-medium">거래액</th>
                    <th className="text-right px-4 py-2 font-medium">비중</th>
                    <th className="text-right px-4 py-2 font-medium">건수</th>
                    <th className="text-right px-4 py-2 font-medium">수수료</th>
                    <th className="text-right px-4 py-2 font-medium">순정산액</th>
                  </tr>
                </thead>
                <tbody>
                  {/* 키에 순번을 섞는 이유: 서버는 식별자로 묶고 이름으로 보여 주므로,
                      같은 이름의 상품이 둘이면 라벨이 겹친다(그룹은 서로 다르다). */}
                  {breakdown.rows.map((row, index) => (
                    <tr key={`${index}-${row.label}`} className="border-t border-gray-100">
                      <td className="px-4 py-2 text-gray-900">
                        {row.label}
                        {row.label === 'UNKNOWN' && (
                          <span className="ml-2 text-xs text-amber-700">프로젝션 미도착</span>
                        )}
                      </td>
                      <td className="px-4 py-2 text-right text-gray-900">{won(row.gmv)}</td>
                      <td className="px-4 py-2 text-right">
                        <span className="inline-flex items-center gap-2">
                          <span className="w-16 h-2 bg-gray-100 rounded overflow-hidden">
                            <span className="block h-full bg-indigo-500"
                              style={{ width: `${Math.min(row.sharePercent, 100)}%` }} />
                          </span>
                          <span className="text-gray-600 tabular-nums">{row.sharePercent.toFixed(2)}%</span>
                        </span>
                      </td>
                      <td className="px-4 py-2 text-right text-gray-600">{row.transactionCount.toLocaleString()}</td>
                      <td className="px-4 py-2 text-right text-gray-600">{won(row.commissionAmount)}</td>
                      <td className="px-4 py-2 text-right text-gray-600">{won(row.netSettlement)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot className="bg-gray-50 text-gray-700 font-semibold">
                  <tr>
                    <td className="px-4 py-2">합계</td>
                    <td className="px-4 py-2 text-right">{won(breakdown.totalGmv)}</td>
                    <td className="px-4 py-2 text-right">100%</td>
                    <td className="px-4 py-2 text-right">{breakdown.totalTransactionCount.toLocaleString()}</td>
                    <td className="px-4 py-2" />
                    <td className="px-4 py-2" />
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
          <p className="text-xs text-gray-500">
            비중은 소수 둘째 자리에서 반올림하므로 합이 정확히 100% 가 아닐 수 있습니다(누락이 아닙니다).
            <b> UNKNOWN</b> 은 정산은 있으나 결제·상품 프로젝션이 아직 도착하지 않은 건입니다 —
            금액은 합계에 그대로 포함됩니다.
          </p>
        </section>
      )}
    </div>
  );
};

export default SalesStatsConsolePage;
