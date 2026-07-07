import { describe, expect, it } from 'vitest';
import {
  buildCeoBriefing,
  computeValuation,
  pickLatestStatement,
  type CeoReputationInput,
  type CeoStatementInput,
} from '@/api/ceo';

const latest: CeoStatementInput = {
  fiscalYear: 2026,
  fsDivision: 'CFS',
  revenue: 120_000_000_000,
  operatingProfit: 4_000_000_000,
  netIncome: 1_000_000_000,
  totalAssets: 90_000_000_000,
  totalLiabilities: 75_000_000_000,
  totalEquity: 15_000_000_000,
  operatingMargin: 3.3,
  netMargin: 0.8,
  debtRatio: 500,
  equityRatio: 16.7,
  roa: 1.1,
  source: 'DART',
};

describe('CEO insight analysis', () => {
  it('selects the most recent consolidated statement first', () => {
    const picked = pickLatestStatement([
      { ...latest, fiscalYear: 2025, fsDivision: 'OFS' },
      { ...latest, fiscalYear: 2024, fsDivision: 'CFS' },
      latest,
    ]);

    expect(picked?.fiscalYear).toBe(2026);
    expect(picked?.fsDivision).toBe('CFS');
  });

  it('builds CEO briefing risks from financial, reputation, and macro signals', () => {
    const reputation: CeoReputationInput = {
      grade: 'D',
      score: 42,
      negativeCount: 7,
      negativeByCategory: { FINANCIAL: 3, GOVERNANCE: 2 },
    };

    const briefing = buildCeoBriefing({
      companyName: '테스트전자',
      statement: latest,
      reputation,
      indicators: [
        {
          code: 'BASE_RATE',
          name: '기준금리',
          unit: '%',
          latest: { observedDate: '2026-06-30', value: 3.75 },
          change: { amount: 0.25, ratePercent: 7.14 },
        },
        {
          code: 'USD_KRW',
          name: 'USD/KRW',
          unit: '원',
          latest: { observedDate: '2026-06-30', value: 1450 },
          change: { amount: 35, ratePercent: 2.47 },
        },
      ],
    });

    expect(briefing.headline).toContain('테스트전자');
    expect(briefing.risks.map((risk) => risk.category)).toEqual(
      expect.arrayContaining(['재무 안정성', '수익성', '기업 평판', '거시 환경'])
    );
    expect(briefing.risks[0].severity).toBe('high');
    expect(briefing.summaryCards.some((card) => card.label === '부채비율')).toBe(true);
  });

  it('computes PER/PBR from market cap joined with the statement', () => {
    const valuation = computeValuation({
      marketCap: 30_000_000_000, // 시총 300억
      netIncome: 1_000_000_000,  // 순이익 10억 → PER 30
      totalEquity: 15_000_000_000, // 자본 150억 → PBR 2
    });

    expect(valuation.per).toBeCloseTo(30);
    expect(valuation.pbr).toBeCloseTo(2);
    expect(valuation.marketCap).toBe(30_000_000_000);
  });

  it('returns null multiples when denominators are missing or non-positive', () => {
    expect(computeValuation({ marketCap: 30_000_000_000, netIncome: 0, totalEquity: -5 })).toEqual({
      marketCap: 30_000_000_000,
      per: null,
      pbr: null,
    });
    expect(computeValuation({ marketCap: null, netIncome: 1_000, totalEquity: 1_000 })).toEqual({
      marketCap: null,
      per: null,
      pbr: null,
    });
  });
});
