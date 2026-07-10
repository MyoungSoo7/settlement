/**
 * 백테스트 순수 계산 코어 (zero-dependency, 네트워크 없음 — 단위 테스트 대상).
 *
 * 목적: "오를 확률 90%" 같은 지어낸 숫자 대신, 유니버스의 실제 과거 시세로
 * 보유기간(월/분기/연)별 승률·수익 분포를 계산해 정직하게 표기할 근거를 만든다.
 *
 * 방법론(결과 JSON 에도 동일 서술을 박는다):
 * - 진입 시점: 약 1개월(21거래일) 간격 롤링. 종목·시점별 전방 수익률 = close[t+H]/close[t]-1.
 * - 승률 = 전방 수익률 > 0 비율. 모멘텀 필터 = 진입 직전 12개월(252거래일) 수익률 > 0.
 * - 한계: 유니버스가 "현재" 대형주라 생존 편향으로 승률이 부풀 수 있음.
 *   배당 제외(가격 수익률), 거래비용 미반영. 과거 승률은 미래를 보장하지 않는다.
 */

export const HORIZONS = { month: 21, quarter: 63, year: 252 };
const ENTRY_STEP_DAYS = 21;
const MOMENTUM_LOOKBACK_DAYS = 252;

/** closes: [{date, close}] → 진입 시점별 전방 수익률 표본.
 *  반환: [{ entryDate, ret, momentum }] (momentum: 진입 직전 12개월 수익률, 표본 부족 시 null) */
export function forwardReturnSamples(closes, horizonDays, {
  stepDays = ENTRY_STEP_DAYS,
  momentumLookback = MOMENTUM_LOOKBACK_DAYS,
} = {}) {
  const samples = [];
  for (let t = 0; t + horizonDays < closes.length; t += stepDays) {
    const entry = closes[t];
    const exit = closes[t + horizonDays];
    if (!entry?.close || !exit?.close) continue;
    const momentum = t >= momentumLookback
      ? entry.close / closes[t - momentumLookback].close - 1
      : null;
    samples.push({
      entryDate: entry.date,
      ret: exit.close / entry.close - 1,
      momentum,
    });
  }
  return samples;
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.round((sorted.length - 1) * p)));
  return sorted[idx];
}

function round4(x) {
  return x === null ? null : Number(x.toFixed(4));
}

/** 표본 → 통계 요약. */
export function summarize(samples) {
  const rets = samples.map((s) => s.ret).sort((a, b) => a - b);
  const wins = samples.filter((s) => s.ret > 0).length;
  const momSamples = samples.filter((s) => s.momentum !== null && s.momentum > 0);
  const momWins = momSamples.filter((s) => s.ret > 0).length;
  const mean = rets.length ? rets.reduce((a, b) => a + b, 0) / rets.length : null;
  return {
    samples: samples.length,
    winRate: samples.length ? round4(wins / samples.length) : null,
    avgReturn: round4(mean),
    medianReturn: round4(percentile(rets, 0.5)),
    p5Return: round4(percentile(rets, 0.05)),
    p95Return: round4(percentile(rets, 0.95)),
    momentumFilter: {
      samples: momSamples.length,
      winRate: momSamples.length ? round4(momWins / momSamples.length) : null,
    },
  };
}

/** 유니버스 전체 시세 → 주기별 통계.
 *  seriesByCode: { [code]: { name, closes } } */
export function computeStats(seriesByCode, horizons = HORIZONS) {
  const stats = {};
  for (const [label, horizonDays] of Object.entries(horizons)) {
    const all = [];
    const perSymbol = [];
    for (const [code, { name, closes }] of Object.entries(seriesByCode)) {
      const samples = forwardReturnSamples(closes, horizonDays);
      all.push(...samples);
      const summary = summarize(samples);
      perSymbol.push({ code, name, samples: summary.samples, winRate: summary.winRate });
    }
    stats[label] = {
      horizonTradingDays: horizonDays,
      ...summarize(all),
      perSymbolWinRateRange: perSymbol.length
        ? {
            min: perSymbol.reduce((a, b) => (b.winRate !== null && b.winRate < a.winRate ? b : a)),
            max: perSymbol.reduce((a, b) => (b.winRate !== null && b.winRate > a.winRate ? b : a)),
          }
        : null,
    };
  }
  return stats;
}
