/**
 * 산업군×시기 규칙 충족도 엔진 (순수 로직 — 네트워크 없음, 결정론).
 *
 * DART 요약재무(fnlttSinglAcnt) 응답 행에서 핵심 계정을 뽑아 재무 규칙 5종의
 * 충족 여부를 판정하고, 종목별 판정을 산업군 단위로 집계한다.
 *
 * 원칙 (플러그인 컴플라이언스와 동일):
 * - 점수는 "투자적합도 예측"이 아니라 **규칙 충족 개수**다. 판정 불가(데이터 부재,
 *   업종 특성상 무의미)는 0점이 아니라 N/A 로 분모에서 제외한다 — 조용한 0 금지.
 * - 금융업(은행·보험·금융지주)은 부채비율 규칙을 적용하지 않는다. 예대·보험부채가
 *   영업의 본질이라 제조업 기준(≤200%)이 무의미하다.
 */

export const RULES = [
  { key: 'revenueGrowth', label: '매출 성장', detail: '당기 매출(영업수익) > 전기' },
  { key: 'opGrowth', label: '영업이익 성장', detail: '당기 영업이익 > 전기' },
  { key: 'opMargin', label: '영업이익률 ≥ 5%', detail: '영업이익 / 매출 ≥ 5%' },
  { key: 'netPositive', label: '순이익 흑자', detail: '당기순이익 > 0' },
  { key: 'debtRatio', label: '부채비율 ≤ 200%', detail: '부채총계/자본총계 ≤ 200% (금융업 N/A)' },
];

export const RULE_COUNT = RULES.length;
const OP_MARGIN_MIN = 0.05;
const DEBT_RATIO_MAX = 2.0;

/** DART 금액 문자열("1,234,567" / "-1,234" / "-" / "") → number | null */
export function parseAmount(value) {
  const s = String(value ?? '').trim();
  if (!s || s === '-') return null;
  const n = Number(s.replace(/,/g, ''));
  return Number.isFinite(n) ? n : null;
}

// 계정과목명은 회사마다 표기가 갈린다 — 후보를 순서대로 탐색 (앞이 우선)
const ACCOUNT_CANDIDATES = {
  revenue: ['매출액', '영업수익', '수익(매출액)', '매출'],
  operatingProfit: ['영업이익', '영업이익(손실)'],
  netIncome: ['당기순이익', '당기순이익(손실)', '분기순이익', '반기순이익', '분기순이익(손실)', '반기순이익(손실)', '당기순손익'],
  liabilities: ['부채총계'],
  equity: ['자본총계'],
};

const normalizeName = name => String(name ?? '').replace(/\s+/g, '');

/**
 * fnlttSinglAcnt 응답 행(list)에서 핵심 계정 추출.
 * 연결(CFS) 행이 하나라도 있으면 연결만, 없으면 별도(OFS)를 쓴다.
 * 반환: { basis: 'CFS'|'OFS'|null, revenue: {thstrm, frmtrm}, ... }
 */
export function extractAccounts(rows) {
  const list = Array.isArray(rows) ? rows : [];
  const hasCfs = list.some(r => r.fs_div === 'CFS');
  const basis = hasCfs ? 'CFS' : (list.length ? 'OFS' : null);
  const scoped = list.filter(r => r.fs_div === basis);

  const pick = (candidates, sjDivs) => {
    for (const name of candidates) {
      const row = scoped.find(r => sjDivs.includes(r.sj_div) && normalizeName(r.account_nm) === normalizeName(name));
      if (row) return { thstrm: parseAmount(row.thstrm_amount), frmtrm: parseAmount(row.frmtrm_amount) };
    }
    return { thstrm: null, frmtrm: null };
  };

  return {
    basis,
    revenue: pick(ACCOUNT_CANDIDATES.revenue, ['IS', 'CIS']),
    operatingProfit: pick(ACCOUNT_CANDIDATES.operatingProfit, ['IS', 'CIS']),
    netIncome: pick(ACCOUNT_CANDIDATES.netIncome, ['IS', 'CIS']),
    liabilities: pick(ACCOUNT_CANDIDATES.liabilities, ['BS']),
    equity: pick(ACCOUNT_CANDIDATES.equity, ['BS']),
  };
}

/**
 * 규칙 5종 판정 — 각 규칙은 true(충족) / false(미충족) / null(판정 불가·N/A).
 * 판정 불가는 분모(applicable)에서 제외한다.
 */
export function evaluateRules(accounts, { isFinancial = false } = {}) {
  const { revenue, operatingProfit, netIncome, liabilities, equity } = accounts;
  const growth = pair => (pair.thstrm != null && pair.frmtrm != null ? pair.thstrm > pair.frmtrm : null);

  const verdicts = {
    revenueGrowth: growth(revenue),
    opGrowth: growth(operatingProfit),
    opMargin: operatingProfit.thstrm != null && revenue.thstrm != null && revenue.thstrm > 0
      ? operatingProfit.thstrm / revenue.thstrm >= OP_MARGIN_MIN
      : null,
    netPositive: netIncome.thstrm != null ? netIncome.thstrm > 0 : null,
    debtRatio: isFinancial
      ? null
      : (liabilities.thstrm != null && equity.thstrm != null && equity.thstrm > 0
        ? liabilities.thstrm / equity.thstrm <= DEBT_RATIO_MAX
        : null),
  };

  const applicable = Object.values(verdicts).filter(v => v !== null).length;
  const satisfied = Object.values(verdicts).filter(v => v === true).length;
  return { verdicts, satisfied, applicable };
}

/** 종목 판정 배열 → 산업군 한 칸 집계. 5점 만점으로 정규화한 평균(avgScore5)을 낸다. */
export function aggregateSectorPeriod(stockResults) {
  const rated = stockResults.filter(s => s.applicable > 0);
  if (!rated.length) {
    return { avgScore5: null, ratedCount: 0, totalCount: stockResults.length };
  }
  const meanRatio = rated.reduce((sum, s) => sum + s.satisfied / s.applicable, 0) / rated.length;
  return {
    avgScore5: Math.round(meanRatio * RULE_COUNT * 10) / 10,
    ratedCount: rated.length,
    totalCount: stockResults.length,
  };
}

/** 직전 시기 대비 추세 화살표 — ±0.3 이내는 보합(─).
 *  점수가 소수 1자리라 델타도 1자리로 반올림해 부동소수점 오차를 제거한다 (3.3-3.0=0.299…). */
export function trendArrow(prev, curr) {
  if (prev == null || curr == null) return '';
  const delta = Math.round((curr - prev) * 10) / 10;
  if (delta >= 0.3) return '▲';
  if (delta <= -0.3) return '▼';
  return '─';
}

/** 셀 표기: "3.2/5 ▲" — 판정 불가 칸은 "미공시" */
export function formatCell(agg, arrow = '') {
  if (!agg || agg.avgScore5 == null) return '미공시';
  return `${agg.avgScore5.toFixed(1)}/${RULE_COUNT}${arrow ? ` ${arrow}` : ''}`;
}
