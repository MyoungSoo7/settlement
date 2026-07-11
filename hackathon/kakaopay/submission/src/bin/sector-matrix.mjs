#!/usr/bin/env node
/**
 * 산업군×시기 규칙 충족도 매트릭스 사전계산 CLI — 유니버스 전 종목의 DART 요약재무를
 * 시기별(직전 3개 사업연도 + 최근 분기)로 수집·판정해 src/data/stats/sector-matrix.json
 * 에 기록한다. backtest.mjs 와 같은 패턴: 심사자가 재실행하면 같은 방법론으로 재계산된다.
 *
 * 실행: node src/bin/sector-matrix.mjs             (66종목 × 4시기 ≈ 264 콜, 1~2분)
 *       node src/bin/sector-matrix.mjs --limit 8   (빠른 확인용)
 * 필요: DART_API_KEY (env 또는 상위 .env)
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { financialSummary } from '../dart/client.mjs';
import { loadCorpCodes } from '../dart/corp-codes.mjs';
import {
  RULES, extractAccounts, evaluateRules, aggregateSectorPeriod,
} from '../common/sector-rules.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const universePath = join(here, '..', 'data', 'universe', 'krx-top.json');
export const STATS_PATH = join(here, '..', 'data', 'stats', 'sector-matrix.json');

const CALL_DELAY_MS = 120; // DART 분당 한도 완화 (backtest.mjs 와 동일 접근)
const ANNUAL_REPORT = '11011';
const QUARTER_REPORTS = { Q1: '11013', HALF: '11012', Q3: '11014' };

/** 오늘 기준 조회 시기 4개 — 직전 3개 사업연도 + 공시됐을 최근 분기보고서 1개 */
export function buildPeriods(today = new Date()) {
  const year = today.getFullYear();
  const month = today.getMonth() + 1;
  const annualYears = [year - 3, year - 2, year - 1];

  // 정기보고서 법정 제출기한 기준(분기 후 45일)으로 "이미 공시됐을" 최신 분기를 고른다
  let quarter;
  if (month >= 12) quarter = { year, reprtCode: QUARTER_REPORTS.Q3, label: `${year} 3분기` };
  else if (month >= 9) quarter = { year, reprtCode: QUARTER_REPORTS.HALF, label: `${year} 반기` };
  else if (month >= 6) quarter = { year, reprtCode: QUARTER_REPORTS.Q1, label: `${year} 1분기` };
  else quarter = { year: year - 1, reprtCode: QUARTER_REPORTS.Q3, label: `${year - 1} 3분기` };

  return [
    ...annualYears.map(y => ({ key: `FY${y}`, year: y, reprtCode: ANNUAL_REPORT, label: `${y} 연간` })),
    { key: 'RECENT_Q', ...quarter },
  ];
}

async function fetchStockPeriod({ corpCode, year, reprtCode }) {
  const body = await financialSummary({ corpCode, year, reprtCode });
  if (body.status === '013' || !Array.isArray(body.list) || !body.list.length) return null; // 미공시
  return extractAccounts(body.list);
}

export async function computeSectorMatrix({ limit = Infinity, log = () => {} } = {}) {
  const universe = JSON.parse(readFileSync(universePath, 'utf8'));
  const financialSectors = new Set(universe.financialSectors ?? []);
  const symbols = universe.symbols.slice(0, limit);
  const periods = buildPeriods();

  const { companies } = await loadCorpCodes();
  const corpByStock = new Map(companies.map(c => [c.stockCode, c.corpCode]));

  const stocks = [];
  const dropped = [];
  let done = 0;
  for (const { code, name, sector } of symbols) {
    const corpCode = corpByStock.get(code);
    if (!corpCode) {
      dropped.push({ code, name, reason: 'corp_code 미발견' });
      continue;
    }
    const isFinancial = financialSectors.has(sector);
    const byPeriod = {};
    for (const period of periods) {
      try {
        const accounts = await fetchStockPeriod({ corpCode, year: period.year, reprtCode: period.reprtCode });
        byPeriod[period.key] = accounts
          ? { ...evaluateRules(accounts, { isFinancial }), basis: accounts.basis }
          : null; // 미공시
      } catch (error) {
        byPeriod[period.key] = null;
        dropped.push({ code, name, period: period.key, reason: String(error.message).slice(0, 80) });
      }
      await delay(CALL_DELAY_MS);
    }
    stocks.push({ code, name, sector, isFinancial, periods: byPeriod });
    done += 1;
    if (done % 10 === 0) log(`  ...${done}/${symbols.length}`);
  }

  // 산업군 집계 — 시기별 평균 점수(5점 정규화)
  const sectorNames = [...new Set(symbols.map(s => s.sector))];
  const sectors = sectorNames.map(sector => {
    const members = stocks.filter(s => s.sector === sector);
    const byPeriod = {};
    for (const period of periods) {
      const rated = members
        .map(m => ({ code: m.code, name: m.name, ...(m.periods[period.key] ?? { satisfied: 0, applicable: 0 }) }))
        .map(({ verdicts, basis, ...rest }) => rest);
      byPeriod[period.key] = aggregateSectorPeriod(rated);
    }
    return { sector, isFinancial: financialSectors.has(sector), stockCount: members.length, periods: byPeriod };
  });

  return {
    generatedAt: new Date().toISOString(),
    universe: { name: universe.name, asOf: universe.asOf, requested: symbols.length, used: stocks.length },
    periods,
    rules: RULES,
    methodology: [
      '점수 = 재무 규칙 5종 중 충족 개수를 5점 만점으로 정규화한 산업군 평균 (판정 불가 규칙은 분모에서 제외)',
      '재무 출처: DART 요약재무(fnlttSinglAcnt) — 연결(CFS) 우선, 없으면 별도(OFS)',
      '금융업(은행·보험·금융지주)은 부채비율 규칙 N/A — 예대·보험부채가 영업의 본질',
      '한계: 유니버스가 현재 대형주 66종목(생존 편향) · 규칙은 과거 공시 재무 기준 · 점수는 예측이 아니라 규칙 충족 서술',
    ],
    dropped,
    sectors,
    stocks,
  };
}

// CLI 진입 (import 시에는 실행 안 함 — sector-report.mjs 가 재사용)
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const limitArg = process.argv.indexOf('--limit');
  const limit = limitArg >= 0 ? Number(process.argv[limitArg + 1]) : Infinity;

  const matrix = await computeSectorMatrix({ limit, log: console.log });
  mkdirSync(dirname(STATS_PATH), { recursive: true });
  writeFileSync(STATS_PATH, JSON.stringify(matrix, null, 2) + '\n');

  console.log(`\nuniverse: ${matrix.universe.used}/${matrix.universe.requested} used, dropped ${matrix.dropped.length}건`);
  const header = ['산업군'.padEnd(14), ...matrix.periods.map(p => p.label.padStart(10))].join(' ');
  console.log(`\n${header}`);
  for (const s of matrix.sectors) {
    const cells = matrix.periods.map(p => {
      const agg = s.periods[p.key];
      return (agg.avgScore5 == null ? '미공시' : `${agg.avgScore5.toFixed(1)}/5(${agg.ratedCount})`).padStart(10);
    });
    console.log([s.sector.padEnd(14), ...cells].join(' '));
  }
  console.log(`\nwritten: ${STATS_PATH}`);
}
