#!/usr/bin/env node
/**
 * backtest-core 단위 테스트 (네트워크 없음 — 합성 시계열로 결정론 검증).
 * 실행: node src/test/backtest-core-test.mjs
 */
import { forwardReturnSamples, summarize, computeStats, HORIZONS } from '../common/backtest-core.mjs';
import { createAssert } from './smoke-harness.mjs';

const { check, finish } = createAssert();

function series(fn, days) {
  return Array.from({ length: days }, (_, i) => ({
    date: `D${String(i).padStart(4, '0')}`,
    close: fn(i),
  }));
}

// [1] 단조 상승 시계열: 모든 전방 수익률 > 0 → 승률 1.0, 모멘텀 필터도 1.0
const up = series((i) => 100 + i, 600);
const upSamples = forwardReturnSamples(up, 21);
check('상승 시계열: 표본 존재', upSamples.length > 20, `got ${upSamples.length}`);
const upStats = summarize(upSamples);
check('상승 시계열: 승률 1.0', upStats.winRate === 1);
check('상승 시계열: 모멘텀 필터 승률 1.0', upStats.momentumFilter.winRate === 1);

// [2] 단조 하락 시계열: 승률 0, 모멘텀 양수 표본 0
const down = series((i) => 1000 - i, 600);
const downStats = summarize(forwardReturnSamples(down, 21));
check('하락 시계열: 승률 0', downStats.winRate === 0);
check('하락 시계열: 모멘텀 양수 표본 0', downStats.momentumFilter.samples === 0);

// [3] 전방 수익률 산술: close[t+H]/close[t]-1 정확성 (기하 성장 2%/일, H=21)
const geom = series((i) => 100 * Math.pow(1.02, i), 300);
const g = forwardReturnSamples(geom, 21)[0];
check('전방 수익률 산술 정확', Math.abs(g.ret - (Math.pow(1.02, 21) - 1)) < 1e-9, `got ${g.ret}`);

// [4] 모멘텀은 진입 이전 252일 데이터가 있어야 계산 (없으면 null)
check('초기 표본 모멘텀 null', forwardReturnSamples(up, 21)[0].momentum === null);
const lateSample = forwardReturnSamples(up, 21).find((s) => s.momentum !== null);
check('252일 이후 표본 모멘텀 산출', Boolean(lateSample));

// [5] percentile 경계: 표본 1개면 p5=p95=중앙값
const one = summarize([{ entryDate: 'D', ret: 0.1, momentum: null }]);
check('표본 1개 percentile 안전', one.p5Return === 0.1 && one.p95Return === 0.1 && one.medianReturn === 0.1);

// [6] computeStats: 주기 3종 모두 산출 + 유니버스 합산
const stats = computeStats({
  AAA: { name: 'A', closes: up },
  BBB: { name: 'B', closes: down },
}, HORIZONS);
check('주기 3종 산출', ['month', 'quarter', 'year'].every((k) => stats[k]?.samples > 0));
check('합산 승률은 0~1 사이', stats.month.winRate > 0 && stats.month.winRate < 1);

// [7] 빈 시계열 안전
const empty = summarize(forwardReturnSamples([], 21));
check('빈 시계열 안전', empty.samples === 0 && empty.winRate === null);

finish();
