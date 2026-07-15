/**
 * 브리핑 유니버스 빌더 — 상장사 전체(코스피·코스닥)를 분기 브리핑 배치가 소비할 수 있는
 * briefing-companies.json 형태로 만든다.
 *
 * corp-codes.mjs 의 캐시(CORPCODE.xml → stock_code 보유 ~4천 건)는 corpCode·name·stockCode 만
 * 준다. 배치는 그 밖에 두 값을 더 요구한다:
 *   - businessNumber(사업자등록번호): 식별 게이트가 체크섬까지 검증 → 실제 값 필요
 *   - market(KOSPI/KOSDAQ): company-service 등록 시 필요
 * 둘 다 CORPCODE.xml 에 없고 DART 기업개황(company.json)이 준다(bizr_no·corp_cls). 그래서 이
 * 빌더는 상장사마다 company.json 을 1콜 보강한다(레이트리밋 대상).
 *
 * 순수 로직(주입된 loadCorpCodes/fetchCompany 로 동작)만 담아 네트워크 0 으로 테스트한다.
 * 키 확인·실제 어댑터 배선은 bin/build-briefing-universe.mjs 가 한다.
 */
import { validateBusinessNumber } from '../registry/client.mjs';

/** DART corp_cls → 시장 라벨. Y 유가증권(KOSPI) / K 코스닥 / N 코넥스 / E 기타(비상장·기타법인) */
export function mapMarket(corpCls) {
  switch (String(corpCls ?? '').trim().toUpperCase()) {
    case 'Y': return 'KOSPI';
    case 'K': return 'KOSDAQ';
    case 'N': return 'KONEX';
    default: return null;
  }
}

/** YYYYMMDD 만 통과(국세청 개업일자 형식) — 그 외는 null 로 떨궈 게이트 진위확인을 생략시킨다. */
function normalizeOpeningDate(value) {
  const digits = String(value ?? '').replace(/\D/g, '');
  return /^\d{8}$/.test(digits) ? digits : null;
}

/**
 * corp 캐시 항목 + DART company.json 본문 → 배치 목록 항목.
 * @returns {{ entry: object|null, reason: string|null }}
 *   entry 가 null 이면 reason 에 제외 사유(사업자번호 무효·시장 미상 등)를 담는다.
 */
export function toBriefingEntry(corp, companyBody, { markets } = {}) {
  const name = String(corp?.name ?? companyBody?.corp_name ?? '').trim();
  const stockCode = String(corp?.stockCode ?? companyBody?.stock_code ?? '').trim();
  const corpCode = String(corp?.corpCode ?? companyBody?.corp_code ?? '').trim();
  if (!name || !/^\d{6}$/.test(stockCode)) {
    return { entry: null, reason: '이름 또는 6자리 종목코드 누락' };
  }

  const market = mapMarket(companyBody?.corp_cls);
  if (!market) return { entry: null, reason: `시장 구분 미상(corp_cls=${companyBody?.corp_cls ?? ''})` };
  if (markets && !markets.includes(market)) return { entry: null, reason: `대상 시장 아님(${market})` };

  const bizr = validateBusinessNumber(companyBody?.bizr_no);
  if (!bizr.valid) return { entry: null, reason: '사업자등록번호 무효(형식/체크섬)' };

  const entry = {
    name,
    stockCode,
    businessNumber: `${bizr.normalized.slice(0, 3)}-${bizr.normalized.slice(3, 5)}-${bizr.normalized.slice(5)}`,
    corpCode,
    market,
  };
  const ceo = String(companyBody?.ceo_nm ?? '').trim();
  const openingDate = normalizeOpeningDate(companyBody?.est_dt);
  if (ceo) entry.representativeName = ceo;
  if (openingDate) entry.openingDate = openingDate;
  return { entry, reason: null };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 상장사 유니버스를 순회하며 DART 로 보강해 배치 목록을 만든다.
 * @param {object} deps
 * @param {() => Promise<{companies: Array}>} deps.loadCorpCodes  corp-codes 로더
 * @param {(corpCode: string) => Promise<object>} deps.fetchCompany  DART company.json 본문 반환
 * @param {object} [opts]
 * @param {string[]} [opts.markets=['KOSPI','KOSDAQ']]  포함할 시장
 * @param {number} [opts.limit]  상위 N 개만(샘플/테스트)
 * @param {number} [opts.delayMs=0]  콜 간 지연(레이트리밋)
 * @param {(msg: object) => void} [opts.onProgress]  진행 콜백
 * @param {(ms: number) => Promise<void>} [opts.sleepFn=sleep]  테스트 주입용
 */
export async function buildUniverse(deps, opts = {}) {
  const { loadCorpCodes, fetchCompany } = deps;
  const markets = opts.markets ?? ['KOSPI', 'KOSDAQ'];
  const delayMs = opts.delayMs ?? 0;
  const onProgress = opts.onProgress ?? (() => {});
  const sleepFn = opts.sleepFn ?? sleep;

  const { companies: listed } = await loadCorpCodes();
  const targets = typeof opts.limit === 'number' ? listed.slice(0, opts.limit) : listed;

  const companies = [];
  const skipped = [];
  const seenStock = new Set();
  for (let i = 0; i < targets.length; i += 1) {
    const corp = targets[i];
    onProgress({ phase: 'fetch', index: i, total: targets.length, name: corp.name, stockCode: corp.stockCode });
    let body;
    try {
      body = await fetchCompany(corp.corpCode);
    } catch (error) {
      skipped.push({ name: corp.name, stockCode: corp.stockCode, reason: `DART 조회 실패: ${error.message}` });
      if (delayMs) await sleepFn(delayMs);
      continue;
    }
    const { entry, reason } = toBriefingEntry(corp, body, { markets });
    if (!entry) {
      skipped.push({ name: corp.name, stockCode: corp.stockCode, reason });
    } else if (seenStock.has(entry.stockCode)) {
      skipped.push({ name: corp.name, stockCode: corp.stockCode, reason: '종목코드 중복' });
    } else {
      seenStock.add(entry.stockCode);
      companies.push(entry);
    }
    if (delayMs && i < targets.length - 1) await sleepFn(delayMs);
  }

  return { markets, total: targets.length, count: companies.length, companies, skipped };
}
