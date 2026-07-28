/**
 * 매매 계획 계산기 (순수 함수, 네트워크 없음).
 *
 * "매입가·매도가 추천"의 정직한 대응물 — 가격 예측이 아니라 **규칙**이다:
 * - 진입: 3분할 매수 밴드 (1차 30% 현재가, 2차 30% -5%, 3차 40% -10%)
 * - 손절: 평균 매수가 대비 -7% (전량)
 * - 익절: 평균 매수가 대비 +20% 도달 시 절반 매도, 잔여분은 손절선을 본전으로 상향
 * 모든 가격은 KRX 호가단위로 내림 처리한다.
 */

/** KRX 호가단위 (2023-01 개편 기준, 코스피·코스닥 공통) */
export function krxTickSize(price) {
  if (price < 2_000) return 1;
  if (price < 5_000) return 5;
  if (price < 20_000) return 10;
  if (price < 50_000) return 50;
  if (price < 200_000) return 100;
  if (price < 500_000) return 500;
  return 1_000;
}

export function roundToTick(price) {
  const tick = krxTickSize(price);
  return Math.floor(price / tick) * tick;
}

const ENTRY_BANDS = [
  { label: '1차', budgetShare: 0.3, priceRatio: 1.0 },
  { label: '2차', budgetShare: 0.3, priceRatio: 0.95 },
  { label: '3차', budgetShare: 0.4, priceRatio: 0.9 },
];
const STOP_LOSS_RATIO = 0.93;   // 평균 매수가 -7%
const TAKE_PROFIT_RATIO = 1.2;  // 평균 매수가 +20%

/**
 * @param {{ price: number, budget?: number }} input 현재가(원), 총 예산(원 — 선택)
 * @returns 진입 밴드·손절/익절 기준가(·예산 시 수량). 예산이 1주에도 못 미치면 { feasible: false }.
 *   budget 생략 시: 수량 없이 주당 가격 레벨만 반환 (평균가는 30/30/40 가중 가정).
 */
export function computeTradePlan({ price, budget }) {
  if (!Number.isFinite(price) || price <= 0) throw new Error(`price 가 유효하지 않습니다: ${price}`);
  const hasBudget = budget !== undefined && budget !== null;
  if (hasBudget && (!Number.isFinite(budget) || budget <= 0)) throw new Error(`budget 이 유효하지 않습니다: ${budget}`);

  if (!hasBudget) {
    // 가격 레벨 전용 모드 — 평균가는 밴드 전량 체결 가정(30/30/40 가중)
    const entries = ENTRY_BANDS.map(({ label, budgetShare, priceRatio }) => ({
      label,
      targetPrice: roundToTick(price * priceRatio),
      budgetShare,
    }));
    const avgEntry = entries.reduce((a, e) => a + e.targetPrice * e.budgetShare, 0);
    return {
      feasible: true,
      mode: 'price-levels-only',
      entries,
      avgEntryIfAllFilled: Math.round(avgEntry),
      exits: {
        stopLoss: {
          price: roundToTick(avgEntry * STOP_LOSS_RATIO),
          rule: '평균 매수가 -7% 도달 시 전량 매도 — 예외를 만들지 않는다',
        },
        takeProfitFirst: {
          price: roundToTick(avgEntry * TAKE_PROFIT_RATIO),
          rule: '평균 매수가 +20% 도달 시 절반 매도, 잔여분 손절선은 본전(평균 매수가)으로 상향',
        },
      },
      notes: [
        '이 가격들은 예측이 아니라 규칙이다 — 2·3차 밴드에 안 오면 그 비중은 집행하지 않는 것이 원칙',
        '수량은 예산을 주면 계산된다 (budget 인자). 모든 가격은 KRX 호가단위 내림, 수수료·세금 미반영',
      ],
    };
  }

  const entries = ENTRY_BANDS.map(({ label, budgetShare, priceRatio }) => {
    const targetPrice = roundToTick(price * priceRatio);
    const alloc = budget * budgetShare;
    const quantity = Math.floor(alloc / targetPrice);
    return { label, targetPrice, quantity, amount: quantity * targetPrice };
  });

  const totalQuantity = entries.reduce((a, e) => a + e.quantity, 0);
  const totalAmount = entries.reduce((a, e) => a + e.amount, 0);
  if (totalQuantity === 0) {
    return {
      feasible: false,
      reason: `예산 ${budget.toLocaleString()}원으로는 1주(현재가 ${price.toLocaleString()}원)도 매수할 수 없습니다`,
    };
  }
  const avgEntry = totalAmount / totalQuantity;

  return {
    feasible: true,
    entries,
    totalQuantity,
    totalAmount: Math.round(totalAmount),
    avgEntryIfAllFilled: Math.round(avgEntry),
    exits: {
      stopLoss: {
        price: roundToTick(avgEntry * STOP_LOSS_RATIO),
        rule: '평균 매수가 -7% 도달 시 전량 매도 — 예외를 만들지 않는다',
      },
      takeProfitFirst: {
        price: roundToTick(avgEntry * TAKE_PROFIT_RATIO),
        rule: '평균 매수가 +20% 도달 시 절반 매도, 잔여분 손절선은 본전(평균 매수가)으로 상향',
      },
    },
    notes: [
      '이 가격들은 예측이 아니라 규칙이다 — 2·3차 밴드에 안 오면 그 비중은 집행하지 않는 것이 원칙',
      '모든 가격은 KRX 호가단위 내림 적용, 수수료·세금 미반영',
    ],
  };
}
