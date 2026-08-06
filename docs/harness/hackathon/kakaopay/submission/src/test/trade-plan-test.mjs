#!/usr/bin/env node
/**
 * trade-plan 단위 테스트 (네트워크 없음, 결정론).
 * 실행: node src/test/trade-plan-test.mjs
 */
import { computeTradePlan, krxTickSize, roundToTick } from '../common/trade-plan.mjs';
import { createAssert } from './smoke-harness.mjs';

const { check, finish } = createAssert();

// [1] KRX 호가단위 경계
check('호가단위: 1,999→1', krxTickSize(1999) === 1);
check('호가단위: 19,999→10 / 20,000→50', krxTickSize(19999) === 10 && krxTickSize(20000) === 50);
check('호가단위: 500,000→1000', krxTickSize(500000) === 1000);
check('호가 내림: 278,050→278,000 (500단위)', roundToTick(278050) === 278000);

// [2] 3분할 밴드 산술 — 100,000원 종목, 예산 1,000만원
const plan = computeTradePlan({ price: 100000, budget: 10_000_000 });
check('밴드 3개', plan.entries.length === 3);
check('1차가 = 현재가', plan.entries[0].targetPrice === 100000);
check('2차가 = -5% 호가내림', plan.entries[1].targetPrice === roundToTick(95000));
check('3차가 = -10% 호가내림', plan.entries[2].targetPrice === roundToTick(90000));
check('총 집행액 ≤ 예산', plan.totalAmount <= 10_000_000, `got ${plan.totalAmount}`);
check('손절가 = 평단 -7% 호가내림', plan.exits.stopLoss.price === roundToTick(plan.avgEntryIfAllFilled * 0.93));
check('익절가 = 평단 +20% 호가내림', plan.exits.takeProfitFirst.price === roundToTick(plan.avgEntryIfAllFilled * 1.2));
check('익절가 > 손절가', plan.exits.takeProfitFirst.price > plan.exits.stopLoss.price);

// [3] 예산 부족 → feasible:false (조용한 0 수량 금지)
const tiny = computeTradePlan({ price: 500000, budget: 100000 });
check('예산 부족 명시', tiny.feasible === false && tiny.reason.includes('매수할 수 없습니다'));

// [4] 입력 검증
let threw = 0;
for (const bad of [{ price: 0, budget: 1 }, { price: 1, budget: -5 }, { price: NaN, budget: 1 }]) {
  try { computeTradePlan(bad); } catch { threw += 1; }
}
check('비정상 입력 3종 모두 예외', threw === 3);

// [5] 규칙임을 명시하는 문구 포함 (예측으로 오인 방지)
check('예측 아님 고지 포함', plan.notes.some((n) => n.includes('예측이 아니라 규칙')));

// [5-1] budget 생략 → 가격 레벨 전용 모드 (수량 없음, 30/30/40 가중 평균가)
const levels = computeTradePlan({ price: 100000 });
check('레벨 모드: feasible + mode 표시', levels.feasible === true && levels.mode === 'price-levels-only');
check('레벨 모드: 밴드 3개, 수량 없음', levels.entries.length === 3 && levels.entries.every((e) => e.quantity === undefined));
const expectedAvg = 100000 * 0.3 + roundToTick(95000) * 0.3 + roundToTick(90000) * 0.4;
check('레벨 모드: 가중 평균가', levels.avgEntryIfAllFilled === Math.round(expectedAvg), `got ${levels.avgEntryIfAllFilled}`);
check('레벨 모드: 손절 -7%/익절 +20%',
  levels.exits.stopLoss.price === roundToTick(expectedAvg * 0.93)
  && levels.exits.takeProfitFirst.price === roundToTick(expectedAvg * 1.2));
check('레벨 모드: 예측 아님 고지', levels.notes.some((n) => n.includes('예측이 아니라 규칙')));

// [6] 심볼 신선도 선택 — 코스닥 종목의 낡은 .KS 잔재가 신선한 .KQ 를 가리면 안 된다
const { pickFreshest } = await import('../price/client.mjs');
const now = 1_800_000_000_000; // 고정 기준시각
const day = 24 * 60 * 60 * 1000;
const staleKS = { result: { meta: { regularMarketTime: (now - 600 * day) / 1000 } }, market: 'KOSPI', symbol: 'X.KS' };
const freshKQ = { result: { meta: { regularMarketTime: (now - 1 * day) / 1000 } }, market: 'KOSDAQ', symbol: 'X.KQ' };
const picked = pickFreshest([staleKS, freshKQ], now);
check('신선도: 낡은 KS 대신 신선한 KQ 선택', picked.symbol === 'X.KQ' && picked.stale === false);
const onlyStale = pickFreshest([staleKS], now);
check('신선도: 낡은 것뿐이면 stale 표시', onlyStale.symbol === 'X.KS' && onlyStale.stale === true);
check('신선도: 후보 없음 → null', pickFreshest([], now) === null);

finish();
