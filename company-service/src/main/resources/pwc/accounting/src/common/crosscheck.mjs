/**
 * INV-8 외부 대사 (crosscheck) — 내부 시산표 연간 합계 ↔ DART 공시 재무제표.
 *
 * 전제: 분석 대상이 코스피·코스닥 상장사(또는 DART 공시 법인)일 때만 활성화된다.
 * 불변식 게이트(INV-1~7)가 내부 일관성을 확정한다면, INV-8 은 "내부적으로는 일관되게
 * 틀린 데이터"를 감사받은 외부 공시와 대조해 잡는 두 번째 방어선이다.
 *
 * 원칙:
 * - 내부 장부는 보통 개별 법인 장부이므로 기본 비교 대상은 별도(OFS) 재무제표다.
 * - 연간(사업보고서 11011) 감사 수치와만 대조한다 — 분기보고서(검토)·잠정실적(감사 전)은
 *   앵커 강도가 다르므로 자동 대사에 쓰지 않는다.
 * - 내부 데이터에 완결된 회계연도(Q1~Q4)가 없으면 판정을 생략한다(지어내지 않음).
 *   연도를 명시했는데 데이터가 그 연도를 못 덮으면 FAIL 로 보고한다.
 * - 단위 차이는 unitScale 로 명시한다 (예: 내부 장부가 백만원 단위면 1000000).
 *
 * 활성화: verify-books CLI 의 --dart-corp-code 플래그 또는 데이터 폴더의
 * analysis-config.json `crosscheck` 섹션. 비상장 고객사는 미설정 시 자동 스킵 — 범용성 유지.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { financialSummary } from '../dart/client.mjs';
import { num } from './csv.mjs';

export const DEFAULT_CROSSCHECK = {
  corpCode: '',        // DART 고유번호 8자리 — 비어 있으면 INV-8 비활성
  year: null,          // 대사 연도 — 없으면 내부 데이터의 최근 완결 회계연도 자동 선택
  fsDiv: 'OFS',        // OFS 별도(기본 — 내부 장부는 개별 법인) / CFS 연결
  unitScale: 1,        // 내부 금액 × unitScale = KRW (예: 백만원 단위 장부 → 1000000)
  tolerancePct: 1,     // 허용 상대 오차 %
};

/** analysis-config.json 의 crosscheck 섹션을 읽어 기본값과 병합한다. */
export function loadCrosscheckConfig(dataDir) {
  const merged = { ...DEFAULT_CROSSCHECK };
  const path = join(dataDir, 'analysis-config.json');
  if (!existsSync(path)) return merged;
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, 'utf8'));
  } catch (e) {
    throw new Error(`analysis-config.json 파싱 실패 (${path}): ${e.message}`);
  }
  const section = parsed.crosscheck ?? {};
  for (const key of Object.keys(DEFAULT_CROSSCHECK)) {
    if (section[key] !== undefined) merged[key] = section[key];
  }
  return merged;
}

/** 내부 시산표에서 Q1~Q4 가 모두 존재하는 회계연도 목록 (오름차순, 숫자). */
export function findCompleteFiscalYears(books) {
  const byYear = new Map();
  for (const q of books.quarters) {
    const m = /^(\d{4})Q([1-4])$/.exec(String(q));
    if (!m) continue;
    const year = Number(m[1]);
    if (!byYear.has(year)) byYear.set(year, new Set());
    byYear.get(year).add(m[2]);
  }
  return [...byYear.entries()]
    .filter(([, quarters]) => quarters.size === 4)
    .map(([year]) => year)
    .sort((a, b) => a - b);
}

/** 해당 연도 Q1~Q4 의 매출·영업이익 내부 합계. */
export function sumInternalYear(books, year) {
  const rows = books.trialBalance.filter((r) => String(r.quarter).startsWith(`${year}Q`));
  const sum = (col) => rows.reduce((acc, r) => acc + num(r[col], `${r.quarter}.${col}`), 0);
  return { sales: sum('sales'), operatingIncome: sum('operating_income') };
}

// DART 주요계정 응답의 손익 계정명 별칭 (기업/업종별 표기 차이).
const SALES_ACCOUNT_NAMES = ['매출액', '수익(매출액)', '영업수익'];
const OI_ACCOUNT_NAMES = ['영업이익', '영업이익(손실)'];

/** fnlttSinglAcnt 응답에서 지정 재무제표 구분(fsDiv)의 매출액·영업이익 당기 금액을 추출. */
export function extractDartTotals(body, fsDiv) {
  const rows = (body.list ?? []).filter((r) => r.fs_div === fsDiv && r.sj_div === 'IS');
  const pick = (names) => {
    const row = rows.find((r) => names.includes(String(r.account_nm).trim()));
    return row ? num(row.thstrm_amount, `DART ${row.account_nm}`) : null;
  };
  return { sales: pick(SALES_ACCOUNT_NAMES), operatingIncome: pick(OI_ACCOUNT_NAMES) };
}

const round2 = (v) => Math.round(v * 100) / 100;

/**
 * INV-8 실행. fetchSummary 는 테스트 주입 지점 (기본: 실제 DART 클라이언트).
 * @returns {{id:'INV-8', name, pass, detail, skipped?:true}}
 */
export async function runDartCrosscheck(books, opts = {}, fetchSummary = financialSummary) {
  const cfg = { ...DEFAULT_CROSSCHECK, ...opts };
  const name = '내부 시산표 연간 합계 = DART 공시 재무제표 (상장사 외부 대사)';
  const mk = (pass, detail, skipped) => ({ id: 'INV-8', name, pass, detail, ...(skipped ? { skipped: true } : {}) });

  const complete = findCompleteFiscalYears(books);
  let year = cfg.year ? Number(cfg.year) : null;
  if (year !== null) {
    if (!complete.includes(year)) {
      return mk(false, `내부 데이터가 ${year}년 Q1~Q4 를 모두 포함하지 않음 (보유 분기: ${books.quarters.join(', ')})`);
    }
  } else {
    if (complete.length === 0) {
      return mk(true, `판정 생략 — 내부 데이터에 완결된 회계연도(Q1~Q4)가 없음 (보유 분기: ${books.quarters.join(', ')})`, true);
    }
    year = complete[complete.length - 1];
  }

  const internal = sumInternalYear(books, year);

  let body;
  try {
    body = await fetchSummary({ corpCode: cfg.corpCode, year, reprtCode: '11011' });
  } catch (e) {
    return mk(false, `DART 조회 실패 — ${e.message}`);
  }
  if (body.status === '013' || !body.list?.length) {
    return mk(false, `DART 에 ${year} 사업보고서 주요계정 없음 (corp_code ${cfg.corpCode}) — 상장/공시 여부 또는 연도 확인`);
  }

  const dart = extractDartTotals(body, cfg.fsDiv);
  if (dart.sales === null || dart.operatingIncome === null) {
    return mk(false, `DART 응답에서 ${cfg.fsDiv} 손익 계정(매출액/영업이익)을 찾지 못함`);
  }

  const details = [];
  const compare = (label, internalVal, dartVal) => {
    const scaled = internalVal * cfg.unitScale;
    const diffPct = dartVal === 0
      ? (scaled === 0 ? 0 : Infinity)
      : (Math.abs(scaled - dartVal) / Math.abs(dartVal)) * 100;
    details.push(`${label}: 내부 ${scaled.toLocaleString('en-US')} vs 공시 ${dartVal.toLocaleString('en-US')} (차이 ${round2(diffPct)}%)`);
    return diffPct <= cfg.tolerancePct;
  };
  const salesOk = compare('매출', internal.sales, dart.sales);
  const oiOk = compare('영업이익', internal.operatingIncome, dart.operatingIncome);

  return mk(salesOk && oiOk, `${year} 사업보고서 ${cfg.fsDiv} 기준 — ${details.join(' / ')}`);
}
