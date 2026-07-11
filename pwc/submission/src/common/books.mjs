/**
 * 장부 로더 + 불변식 엔진 — 재사용 가능한 진단 프레임워크의 코어.
 *
 * 원칙 (doc/회계.md): "불변식으로 확정할 수 있는 것을 먼저 기계적으로 확정하고,
 * 그 위에서만 AI 추론이 의미를 갖는다."
 *
 * 일반화 포인트:
 * - 특정 분기('2026Q2' 등) 하드코딩 없음 — 항상 데이터에서 최근 분기를 파생.
 * - 컬럼 별칭 지원 — 한국어 헤더(매출/매출채권/거래처 …)로 내보낸 CSV 도 그대로 사용.
 * - 실패 메시지는 "무엇이 없고 무엇이 와야 하는지"를 사람이 읽을 수 있게.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { parseCsv, num } from './csv.mjs';

export const REQUIRED_FILES = ['trial_balance.csv', 'ar_aging.csv', 'cost_allocation.csv'];
export const AMOUNT_TOLERANCE = 0.01;
export const AGING_BUCKETS = ['current', 'd31_60', 'd61_90', 'd90_plus'];

/** 데이터 로드 실패 — 게이트가 "데이터 품질 리스크"로 보고해야 하는 오류. */
export class BooksLoadError extends Error {}

// 컬럼 별칭 — 정규화 시 canonical 이름으로 통일한다 (대소문자 무시).
const COLUMN_ALIASES = {
  'trial_balance.csv': {
    quarter: ['quarter', '분기', '기간', 'period'],
    sales: ['sales', '매출', '매출액', 'revenue'],
    accounts_receivable: ['accounts_receivable', '매출채권', 'ar'],
    inventory: ['inventory', '재고', '재고자산'],
    contract_liability: ['contract_liability', '계약부채', '선수금'],
    operating_income: ['operating_income', '영업이익'],
    operating_cash_flow: ['operating_cash_flow', '영업현금흐름', '영업활동현금흐름', 'ocf'],
    variable_rate_debt: ['variable_rate_debt', '변동금리차입금', '변동금리차입'],
    interest_expense: ['interest_expense', '이자비용'],
  },
  'ar_aging.csv': {
    quarter: ['quarter', '분기', '기간', 'period'],
    customer: ['customer', '거래처', '거래처명'],
    current: ['current', '정상', '미도래', '30일이내'],
    d31_60: ['d31_60', '31_60', '31-60', '31~60일'],
    d61_90: ['d61_90', '61_90', '61-90', '61~90일'],
    d90_plus: ['d90_plus', '90_plus', '90+', '90일초과'],
  },
  'cost_allocation.csv': {
    product: ['product', '제품', '제품명', '사업부'],
    sales: ['sales', '매출', '매출액'],
    direct_cost: ['direct_cost', '직접원가'],
    allocated_common_cost: ['allocated_common_cost', '공통원가배부액', '배부공통원가'],
    allocation_basis_sales_pct: ['allocation_basis_sales_pct', '배부기준비중', '매출비중'],
    actual_machine_hours_pct: ['actual_machine_hours_pct', '기계시간비중', '실제기계시간비중'],
    operating_income: ['operating_income', '영업이익'],
  },
};

function normalizeRows(fileName, { columns, rows }) {
  const aliases = COLUMN_ALIASES[fileName];
  const lowered = new Map(columns.map((c) => [c.toLowerCase().trim(), c]));
  const mapping = {}; // canonical -> 실제 헤더
  const missing = [];
  for (const [canonical, names] of Object.entries(aliases)) {
    const found = names.map((n) => lowered.get(n.toLowerCase())).find(Boolean);
    if (found) mapping[canonical] = found;
    else missing.push(`${canonical} (허용 별칭: ${names.join(', ')})`);
  }
  if (missing.length > 0) {
    throw new BooksLoadError(
      `${fileName}: 필수 컬럼 누락 — ${missing.join(' / ')}. 현재 헤더: [${columns.join(', ')}]`);
  }
  return rows.map((row) =>
    Object.fromEntries(Object.entries(mapping).map(([canonical, actual]) => [canonical, row[actual]])));
}

/**
 * 데이터 디렉터리에서 장부 3종을 로드·정규화한다.
 * @returns {{ dataDir, trialBalance, arAging, costAllocation, quarters, latestQuarter }}
 */
export function loadBooks(dataDir) {
  if (!existsSync(dataDir)) {
    throw new BooksLoadError(`데이터 디렉터리 없음: ${dataDir}`);
  }
  const missingFiles = REQUIRED_FILES.filter((f) => !existsSync(join(dataDir, f)));
  if (missingFiles.length > 0) {
    throw new BooksLoadError(
      `필수 파일 누락 (${dataDir}): ${missingFiles.join(', ')} — `
      + `trial_balance.csv(분기별 시산표) / ar_aging.csv(거래처별 채권 aging) / cost_allocation.csv(제품별 원가배분) 3종이 필요합니다.`);
  }
  const read = (name) => normalizeRows(name, parseCsv(readFileSync(join(dataDir, name), 'utf8')));

  const trialBalance = read('trial_balance.csv');
  const arAging = read('ar_aging.csv');
  const costAllocation = read('cost_allocation.csv');
  if (trialBalance.length === 0) throw new BooksLoadError('trial_balance.csv 에 데이터 행이 없습니다.');
  if (arAging.length === 0) throw new BooksLoadError('ar_aging.csv 에 데이터 행이 없습니다.');
  if (costAllocation.length === 0) throw new BooksLoadError('cost_allocation.csv 에 데이터 행이 없습니다.');

  // 분기 정렬 — YYYYQn 형식은 문자열 정렬이 시간 순서와 일치한다.
  const sorted = [...trialBalance].sort((a, b) => String(a.quarter).localeCompare(String(b.quarter)));
  const quarters = sorted.map((r) => r.quarter);
  return {
    dataDir,
    trialBalance: sorted,
    arAging,
    costAllocation,
    quarters,
    latestQuarter: quarters[quarters.length - 1],
  };
}

const near = (a, b) => Math.abs(a - b) <= AMOUNT_TOLERANCE;

/**
 * 불변식 7종 검증 — 어떤 회사 데이터에도 동일하게 적용되는 기계적 정합성.
 * @returns {{ gate: 'PASS'|'FAIL', checks: Array<{id,name,pass,detail}> }}
 */
export function runInvariants(books) {
  const { trialBalance, arAging, costAllocation, latestQuarter } = books;
  const tbByQuarter = new Map(trialBalance.map((r) => [r.quarter, r]));
  const results = [];
  const check = (id, name, fn) => {
    try {
      const detail = fn();
      results.push({ id, name, pass: true, detail: detail ?? '' });
    } catch (error) {
      results.push({ id, name, pass: false, detail: error.message });
    }
  };

  // INV-1: 시산표 수치 필드 전체 파싱 가능
  check('INV-1', '시산표 수치 필드가 전부 유효한 숫자', () => {
    const numericCols = Object.keys(trialBalance[0]).filter((c) => c !== 'quarter');
    for (const row of trialBalance) {
      for (const col of numericCols) num(row[col], `trial_balance ${row.quarter}.${col}`);
    }
    return `${trialBalance.length}개 분기 × ${numericCols.length}개 컬럼`;
  });

  // INV-2: 채권 aging 분기 합계 = 시산표 매출채권 (대사)
  check('INV-2', '채권 aging 분기 합계 = 시산표 매출채권 (대사 일치)', () => {
    const agingByQuarter = new Map();
    for (const row of arAging) {
      const sum = AGING_BUCKETS.reduce(
        (acc, b) => acc + num(row[b], `ar_aging ${row.quarter}/${row.customer}.${b}`), 0);
      agingByQuarter.set(row.quarter, (agingByQuarter.get(row.quarter) ?? 0) + sum);
    }
    const details = [];
    for (const [quarter, total] of agingByQuarter) {
      const tb = tbByQuarter.get(quarter);
      if (!tb) throw new Error(`aging 의 분기 ${quarter} 가 시산표에 없음`);
      const expected = num(tb.accounts_receivable, `trial_balance ${quarter}.accounts_receivable`);
      if (!near(total, expected)) {
        throw new Error(`${quarter}: aging 합계 ${total} ≠ 시산표 매출채권 ${expected}`);
      }
      details.push(`${quarter}=${total}`);
    }
    return details.join(', ');
  });

  // INV-3: 원가표 매출 합계 = 시산표 "최근 분기" 매출 (분기는 데이터에서 파생)
  check('INV-3', `원가배분표 매출 합계 = 시산표 최근 분기(${latestQuarter}) 매출`, () => {
    const total = costAllocation.reduce((acc, r) => acc + num(r.sales, `cost_allocation ${r.product}.sales`), 0);
    const expected = num(tbByQuarter.get(latestQuarter).sales, `trial_balance ${latestQuarter}.sales`);
    if (!near(total, expected)) throw new Error(`원가표 매출 합 ${total} ≠ 시산표 ${latestQuarter} 매출 ${expected}`);
    return `${total}`;
  });

  // INV-4: 공통원가 배부액이 배부 기준(매출 비중)과 비례
  check('INV-4', '공통원가 배부액 = 공통원가 총액 × 배부 기준 비중', () => {
    const totalCommon = costAllocation.reduce(
      (acc, r) => acc + num(r.allocated_common_cost, `cost_allocation ${r.product}.allocated_common_cost`), 0);
    for (const row of costAllocation) {
      const expected = (totalCommon * num(row.allocation_basis_sales_pct, `${row.product}.allocation_basis_sales_pct`)) / 100;
      const actual = num(row.allocated_common_cost, `${row.product}.allocated_common_cost`);
      if (!near(actual, expected)) {
        throw new Error(`${row.product}: 배부액 ${actual} ≠ 총액 ${totalCommon} × 비중 → ${expected}`);
      }
    }
    return `공통원가 총액 ${totalCommon}`;
  });

  // INV-5: 배부 기준 비중 합 100%, 실제 기계시간 비중 합 100%
  check('INV-5', '배부 기준 비중 합계 100% / 실제 기계시간 비중 합계 100%', () => {
    const basisSum = costAllocation.reduce((acc, r) => acc + num(r.allocation_basis_sales_pct, 'basis_pct'), 0);
    const hoursSum = costAllocation.reduce((acc, r) => acc + num(r.actual_machine_hours_pct, 'hours_pct'), 0);
    if (!near(basisSum, 100)) throw new Error(`배부 기준 비중 합 ${basisSum} ≠ 100`);
    if (!near(hoursSum, 100)) throw new Error(`기계시간 비중 합 ${hoursSum} ≠ 100`);
    return `기준 ${basisSum} / 기계시간 ${hoursSum}`;
  });

  // INV-6: 제품별 영업이익 = 매출 − 직접원가 − 배부 공통원가
  check('INV-6', '제품별 영업이익 검산 (매출 − 직접원가 − 배부 공통원가)', () => {
    for (const row of costAllocation) {
      const expected = num(row.sales, 'sales') - num(row.direct_cost, 'direct_cost')
        - num(row.allocated_common_cost, 'allocated_common_cost');
      const actual = num(row.operating_income, `${row.product}.operating_income`);
      if (!near(actual, expected)) throw new Error(`${row.product}: 영업이익 ${actual} ≠ 검산 ${expected}`);
    }
    return costAllocation.map((r) => `${r.product}=${r.operating_income}`).join(', ');
  });

  // INV-7: 차입·이자 필드 유효성 — 음수 금지 (무차입 경영 debt=0 은 정상)
  check('INV-7', '변동금리 차입·이자비용 필드 유효 (음수 금지, 이자보상배율 파생 가능)', () => {
    const details = [];
    for (const row of trialBalance) {
      const debt = num(row.variable_rate_debt, `${row.quarter}.variable_rate_debt`);
      const interest = num(row.interest_expense, `${row.quarter}.interest_expense`);
      if (debt < 0 || interest < 0) throw new Error(`${row.quarter}: 차입 ${debt} / 이자 ${interest} — 음수 불가`);
      const oi = num(row.operating_income, `${row.quarter}.operating_income`);
      details.push(interest > 0 ? `${row.quarter} 이자보상 ${(oi / interest).toFixed(1)}배` : `${row.quarter} 무이자`);
    }
    return details.join(', ');
  });

  const failures = results.filter((r) => !r.pass);
  return { gate: failures.length === 0 ? 'PASS' : 'FAIL', checks: results };
}

/** CLI 공용 — --data-dir 플래그 > VERIFY_BOOKS_DATA_DIR env > 기본 샘플 경로. */
export function resolveDataDir(argv, defaultDir) {
  const i = argv.indexOf('--data-dir');
  if (i !== -1 && argv[i + 1]) return argv[i + 1];
  return process.env.VERIFY_BOOKS_DATA_DIR || defaultDir;
}
