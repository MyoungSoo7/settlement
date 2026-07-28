#!/usr/bin/env node
/**
 * 유니버스 백테스트 CLI — 실 시세(Yahoo, 10년 일별 종가)로 월/분기/연 보유 승률을 계산해
 * src/data/stats/backtest-stats.json 에 기록한다. 심사자가 재실행하면 같은 방법론으로
 * 재계산된다 (숫자는 데이터 갱신에 따라 달라질 수 있음 — 그게 정직한 것이다).
 *
 * 실행: node src/bin/backtest.mjs            (약 1~2분, 네트워크 필요)
 *       node src/bin/backtest.mjs --limit 5  (빠른 확인용)
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { dailyCloses } from '../price/client.mjs';
import { computeStats, HORIZONS } from '../common/backtest-core.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const universePath = join(here, '..', 'data', 'universe', 'krx-top.json');
const outPath = join(here, '..', 'data', 'stats', 'backtest-stats.json');

const limitArg = process.argv.indexOf('--limit');
const limit = limitArg >= 0 ? Number(process.argv[limitArg + 1]) : Infinity;

const universe = JSON.parse(readFileSync(universePath, 'utf8'));
const symbols = universe.symbols.slice(0, limit);

const seriesByCode = {};
const dropped = [];
let done = 0;
for (const { code, name } of symbols) {
  try {
    const { closes, market } = await dailyCloses(code, { range: '10y' });
    if (closes.length < 300) {
      dropped.push({ code, name, reason: `history too short (${closes.length} days)` });
    } else {
      seriesByCode[code] = { name, market, closes };
    }
  } catch (error) {
    dropped.push({ code, name, reason: String(error.message).slice(0, 80) });
  }
  done += 1;
  if (done % 10 === 0) console.log(`  ...${done}/${symbols.length}`);
  await delay(150);
}

const stats = computeStats(seriesByCode);

const report = {
  generatedAt: new Date().toISOString(),
  universe: {
    name: universe.name,
    asOf: universe.asOf,
    requested: symbols.length,
    used: Object.keys(seriesByCode).length,
    dropped,
  },
  methodology: [
    '진입 시점 21거래일 간격 롤링, 전방 수익률 = close[t+H]/close[t]-1 (H: 월 21 / 분기 63 / 연 252 거래일)',
    '승률 = 전방 수익률 > 0 비율. 모멘텀 필터 = 진입 직전 252거래일 수익률 > 0 인 표본만',
    '한계: 현재 대형주 유니버스라 생존 편향으로 승률 과대 가능 · 배당 제외 · 거래비용 미반영',
    '과거 승률은 미래 수익을 보장하지 않는다 — 이 통계는 기대치 설정용이지 예측이 아니다',
  ],
  horizons: stats,
};

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(report, null, 2) + '\n');

console.log(`\nuniverse: ${report.universe.used}/${report.universe.requested} used, ${dropped.length} dropped`);
for (const [label, s] of Object.entries(stats)) {
  console.log(`${label.padEnd(8)} samples=${s.samples}  winRate=${(s.winRate * 100).toFixed(1)}%  momentumWinRate=${(s.momentumFilter.winRate * 100).toFixed(1)}% (n=${s.momentumFilter.samples})  median=${(s.medianReturn * 100).toFixed(1)}%  p5=${(s.p5Return * 100).toFixed(1)}%`);
}
console.log(`\nwritten: ${outPath}`);
if (dropped.length) console.log('dropped:', dropped.map((d) => `${d.name}(${d.code})`).join(', '));
