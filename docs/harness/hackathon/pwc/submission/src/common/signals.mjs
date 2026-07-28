/**
 * 신호 파생 엔진 — "정답지"를 코드에 하드코딩하지 않고 데이터에서 파생 계산한다.
 *
 * 채점기(briefing-eval)와 에이전트(detect-signals CLI)가 이 모듈 하나를 공유하므로,
 * 어떤 회사의 CSV 를 넣어도 (1) 같은 기준으로 신호가 판정되고 (2) 같은 근거 수치로
 * 브리핑이 채점된다. 임계값은 데이터 디렉터리의 analysis-config.json 으로 조정 가능.
 *
 * 신호 4종 (범용 탐지 규칙):
 *   S1 수익-현금 괴리   — 매출채권 증가율이 매출 증가율을 크게 상회 + 계약부채/영업CF 악화 동행
 *   S2 거래처 신용 집중 — 90일 초과 채권이 특정 거래처에 집중 + 전체 채권 대비 유의미한 규모
 *   S3 원가 배분 왜곡   — 배부 기준 비중과 실제 자원(기계시간) 비중의 괴리 + 재배부 시 손익 재계산
 *   S4 차입 의존·금리 노출 — 변동금리 차입 급증 + 이자보상배율 악화
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { num } from './csv.mjs';
import { AGING_BUCKETS } from './books.mjs';

export const DEFAULT_THRESHOLDS = {
  arGrowthGapPp: 15,            // S1: (채권 증가율 − 매출 증가율) 최소 격차 %p
  contractLiabilityDropPct: 20, // S1: 계약부채 감소율 동행 신호
  concentrationSharePct: 60,    // S2: 90일+ 채권의 최대 거래처 점유율
  overdueMaterialityPct: 5,     // S2: 90일+ 채권 / 전체 매출채권 최소 비중 (중요성)
  allocationGapPp: 15,          // S3: |배부 기준 비중 − 실제 기계시간 비중| 최소 격차 %p
  debtGrowthPct: 50,            // S4: 관측 구간 변동금리 차입 증가율
  coverageDropPct: 40,          // S4: 이자보상배율 하락률
  coverageFloor: 10,            // S4: 이자보상배율 절대 하한 (배)
};

/** 데이터 디렉터리의 analysis-config.json(선택)으로 임계값을 오버라이드한다. */
export function loadThresholds(dataDir) {
  const path = join(dataDir, 'analysis-config.json');
  if (!existsSync(path)) return { ...DEFAULT_THRESHOLDS };
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, 'utf8'));
  } catch (e) {
    throw new Error(`analysis-config.json 파싱 실패 (${path}): ${e.message}`);
  }
  const overrides = parsed.thresholds ?? parsed;
  const merged = { ...DEFAULT_THRESHOLDS };
  for (const key of Object.keys(DEFAULT_THRESHOLDS)) {
    if (overrides[key] !== undefined) merged[key] = Number(overrides[key]);
  }
  return merged;
}

// ── 마커 빌더 — 계산값에서 채점용 정규식을 생성 ──────────────
const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** 정수 금액 마커 — 1750 → 1[,.]?750 (천단위 콤마 유무 허용, 음수는 부호 표기, 숫자 경계 보호) */
export function amountMarker(value) {
  const abs = Math.abs(Math.round(value));
  const digits = String(abs);
  let body = '';
  for (let i = 0; i < digits.length; i += 1) {
    body += digits[i];
    const remaining = digits.length - 1 - i;
    if (remaining > 0 && remaining % 3 === 0) body += '[,.]?';
  }
  return value < 0
    ? new RegExp(`[-−▲△]\\s?${body}(?!\\d)`)
    : new RegExp(`(?<![\\d,.])${body}(?!\\d)`);
}

/** 비율 마커(소수 1자리) — 46.94 → 46[.,]9 / 정수로 떨어지면 80 또는 80.0 둘 다 허용 */
export function pctMarker(value) {
  const rounded = (Math.round(Math.abs(value) * 10) / 10).toFixed(1);
  const [intPart, decPart] = rounded.split('.');
  return decPart === '0'
    ? new RegExp(`(?<!\\d)${intPart}([.,]0)?(?![\\d.,])`)
    : new RegExp(`(?<!\\d)${intPart}[.,]${decPart}(?!\\d)`);
}

/** 정수 퍼센트 마커 — 45 → 45 % 허용, 숫자 경계 보호 */
export function intPctMarker(value) {
  return new RegExp(`(?<!\\d)${Math.round(value)}\\s?%`);
}

const round1 = (v) => Math.round(v * 10) / 10;
const growthPct = (prev, cur) => (prev === 0 ? null : ((cur - prev) / Math.abs(prev)) * 100);

/**
 * 장부에서 신호 4종을 파생한다.
 * @param {ReturnType<import('./books.mjs').loadBooks>} books
 * @returns Array<{id,name,present,evaluable,note,evidence,markers,categoryPattern,checkHints}>
 */
export function deriveSignals(books, thresholds = DEFAULT_THRESHOLDS) {
  const { trialBalance, arAging, costAllocation } = books;
  const signals = [];
  const n = (row, col) => num(row[col], `${row.quarter ?? row.product}.${col}`);

  // ── S1: 수익-현금 괴리 (최근 두 분기 비교) ─────────────────
  {
    const id = 'S1';
    const name = '수익-현금 괴리 (수익 조기 인식 의심)';
    const categoryPattern = /수익\s*(조기)?\s*인식|조기\s*인식|밀어내기|현금\s*(이|이랑|과)?\s*따라오지/;
    if (trialBalance.length < 2) {
      signals.push({ id, name, present: false, evaluable: false, note: '분기 2개 이상 필요', evidence: {}, markers: [], categoryPattern, checkHints: [] });
    } else {
      const prev = trialBalance[trialBalance.length - 2];
      const cur = trialBalance[trialBalance.length - 1];
      const salesGrowth = growthPct(n(prev, 'sales'), n(cur, 'sales'));
      const arGrowth = growthPct(n(prev, 'accounts_receivable'), n(cur, 'accounts_receivable'));
      const clPrev = n(prev, 'contract_liability');
      const clCur = n(cur, 'contract_liability');
      const clDropPct = clPrev > 0 ? ((clPrev - clCur) / clPrev) * 100 : 0;
      const ocfPrev = n(prev, 'operating_cash_flow');
      const ocfCur = n(cur, 'operating_cash_flow');
      const ocfTurnedNegative = ocfPrev > 0 && ocfCur <= 0;

      const gapPp = salesGrowth !== null && arGrowth !== null ? arGrowth - salesGrowth : null;
      const corroborated = clDropPct >= thresholds.contractLiabilityDropPct || ocfTurnedNegative;
      const present = gapPp !== null && gapPp >= thresholds.arGrowthGapPp && corroborated;

      const markers = present ? [
        pctMarker(salesGrowth),
        pctMarker(arGrowth),
        amountMarker(ocfCur),
        /계약부채|선수금/,
      ] : [];
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          window: `${prev.quarter}→${cur.quarter}`,
          salesGrowthPct: salesGrowth === null ? null : round1(salesGrowth),
          arGrowthPct: arGrowth === null ? null : round1(arGrowth),
          gapPp: gapPp === null ? null : round1(gapPp),
          contractLiability: `${clPrev}→${clCur}`,
          contractLiabilityDropPct: round1(clDropPct),
          operatingCashFlow: `${ocfPrev}→${ocfCur}`,
          ocfTurnedNegative,
        },
        markers, categoryPattern,
        checkHints: ['기말 ±7일 매출 전표 분포 (cut-off)', '계약 진행률·검수 완료 증빙', '기말 전후 반품/취소 내역'],
      });
    }
  }

  // ── S2: 거래처 신용 집중 (최근 aging 분기) ─────────────────
  {
    const id = 'S2';
    const name = '특정 거래처 신용 집중';
    const categoryPattern = /신용\s*집중|거래처\s*(한\s*곳|집중|쏠림)|(장기\s*)?미수.*집중|집중.*(채권|미수)/;
    const agingQuarters = [...new Set(arAging.map((r) => r.quarter))].sort();
    if (agingQuarters.length === 0) {
      signals.push({ id, name, present: false, evaluable: false, note: 'aging 데이터 없음', evidence: {}, markers: [], categoryPattern, checkHints: [] });
    } else {
      const latest = agingQuarters[agingQuarters.length - 1];
      const rows = arAging.filter((r) => r.quarter === latest);
      const overdueByCustomer = rows.map((r) => ({ customer: r.customer, overdue: n(r, 'd90_plus') }));
      const overdueTotal = overdueByCustomer.reduce((acc, r) => acc + r.overdue, 0);
      const arTotal = rows.reduce((acc, r) => acc + AGING_BUCKETS.reduce((s, b) => s + n(r, b), 0), 0);
      const top = overdueByCustomer.reduce((a, b) => (b.overdue > a.overdue ? b : a), { customer: '', overdue: 0 });
      const sharePct = overdueTotal > 0 ? (top.overdue / overdueTotal) * 100 : 0;
      const materialityPct = arTotal > 0 ? (overdueTotal / arTotal) * 100 : 0;
      const present = sharePct >= thresholds.concentrationSharePct
        && materialityPct >= thresholds.overdueMaterialityPct;

      const markers = present ? [
        pctMarker(sharePct),
        amountMarker(top.overdue),
        new RegExp(escapeRegExp(top.customer)),
      ] : [];
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          quarter: latest,
          overdueTotal,
          topCustomer: top.customer,
          topOverdue: top.overdue,
          topSharePct: round1(sharePct),
          overdueToArPct: round1(materialityPct),
        },
        markers, categoryPattern,
        checkHints: ['해당 거래처 여신 한도·담보 설정 내역', '회수 계획·분할 상환 약정', '대손충당금 설정 적정성'],
      });
    }
  }

  // ── S3: 원가 배분 왜곡 (기계시간 기준 재배부 민감도) ───────
  {
    const id = 'S3';
    const name = '원가 배분 왜곡 (보이지 않는 손실)';
    const categoryPattern = /원가\s*배분|배부\s*(기준|왜곡)|재배부|보이지\s*않는\s*손실|원가.*왜곡/;
    const totalCommon = costAllocation.reduce((acc, r) => acc + n(r, 'allocated_common_cost'), 0);
    const perProduct = costAllocation.map((r) => {
      const basisPct = n(r, 'allocation_basis_sales_pct');
      const machinePct = n(r, 'actual_machine_hours_pct');
      const reallocated = (totalCommon * machinePct) / 100;
      const recomputedIncome = n(r, 'sales') - n(r, 'direct_cost') - reallocated;
      const reportedIncome = n(r, 'operating_income');
      return {
        product: r.product, basisPct, machinePct,
        gapPp: Math.abs(machinePct - basisPct),
        reallocated: Math.round(reallocated),
        reportedIncome, recomputedIncome: Math.round(recomputedIncome),
        flipped: reportedIncome > 0 && recomputedIncome < 0,
      };
    });
    // 손익이 뒤집힌(flipped) 제품이 "보이지 않는 손실"의 본체 — 괴리 크기보다 우선한다.
    const distorted = perProduct
      .filter((p) => p.gapPp >= thresholds.allocationGapPp)
      .sort((a, b) => (b.flipped - a.flipped) || (b.gapPp - a.gapPp));
    const worst = distorted[0] ?? null;
    const present = worst !== null;

    const markers = present ? [
      amountMarker(worst.recomputedIncome),
      /기계\s?시간|자원\s?(소비|사용량)/,
      amountMarker(worst.reallocated),
      intPctMarker(worst.machinePct),
    ] : [];
    signals.push({
      id, name, present, evaluable: true, note: '',
      evidence: {
        commonCostTotal: totalCommon,
        distortedProducts: distorted.map((p) => ({
          product: p.product, basisPct: p.basisPct, machinePct: p.machinePct,
          reallocatedCommonCost: p.reallocated,
          reportedIncome: p.reportedIncome, recomputedIncome: p.recomputedIncome,
          flipped: p.flipped,
        })),
      },
      markers, categoryPattern,
      checkHints: ['기계시간 로그 원천 데이터로 배부율 재산정', '활동기준원가(ABC) 시범 적용', '왜곡 제품 가격·생산·철수 검토'],
    });
  }

  // ── S4: 차입 의존 성장·금리 노출 (관측 구간 전체) ──────────
  {
    const id = 'S4';
    const name = '차입 의존 성장·금리 노출';
    const categoryPattern = /차입\s*의존|금리\s*노출|이자보상|이자\s*부담.*(급증|가속)/;
    if (trialBalance.length < 2) {
      signals.push({ id, name, present: false, evaluable: false, note: '분기 2개 이상 필요', evidence: {}, markers: [], categoryPattern, checkHints: [] });
    } else {
      const first = trialBalance[0];
      const last = trialBalance[trialBalance.length - 1];
      const debtFirst = n(first, 'variable_rate_debt');
      const debtLast = n(last, 'variable_rate_debt');
      const debtGrowth = growthPct(debtFirst, debtLast);
      const coverage = (row) => {
        const interest = n(row, 'interest_expense');
        return interest > 0 ? n(row, 'operating_income') / interest : null;
      };
      const covFirst = coverage(first);
      const covLast = coverage(last);
      const covDropPct = covFirst && covLast ? ((covFirst - covLast) / covFirst) * 100 : null;
      const present = debtGrowth !== null && debtGrowth >= thresholds.debtGrowthPct
        && covLast !== null
        && ((covDropPct !== null && covDropPct >= thresholds.coverageDropPct) || covLast < thresholds.coverageFloor);

      const markers = present ? [
        /이자보상/,
        pctMarker(covLast),
        /변동금리/,
        amountMarker(debtLast),
      ] : [];
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          window: `${first.quarter}→${last.quarter}`,
          variableRateDebt: `${debtFirst}→${debtLast}`,
          debtGrowthPct: debtGrowth === null ? null : round1(debtGrowth),
          interestCoverage: `${covFirst === null ? 'n/a' : round1(covFirst)}→${covLast === null ? 'n/a' : round1(covLast)}배`,
          coverageDropPct: covDropPct === null ? null : round1(covDropPct),
        },
        markers, categoryPattern,
        checkHints: ['차입 약정서 금리 조건(변동/고정) 확인', '금리 헤지(IRS 등) 계약 여부', '차입 상환 스케줄 대비 현금흐름'],
      });
    }
  }

  return signals;
}
