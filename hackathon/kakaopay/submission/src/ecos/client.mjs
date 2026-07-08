/**
 * 한국은행 ECOS OpenAPI 클라이언트 (zero-dependency, Node 18+).
 *
 * 인증: URL 경로에 인증키 포함 (env ECOS_API_KEY).
 * 키가 env 에 없으면 상위 디렉터리의 .env 에서 ECOS_API_KEY= 라인을 찾아 폴백한다
 * (해커톤 로컬 실행 편의 — 운영 배포 시엔 env 주입만 사용할 것).
 *
 * StatisticSearch URL 형식 (이 저장소 economics-service 의 EcosApiClient 와 동일):
 *   {BASE}/StatisticSearch/{apiKey}/json/kr/1/10000/{statCode}/{cycle}/{start}/{end}/{itemCode}
 *
 * 응답 계약:
 *  - 정상: {"StatisticSearch":{"list_total_count":n,"row":[{"TIME":"...","DATA_VALUE":"..."}]}}
 *  - 오류: HTTP 200 에 {"RESULT":{"CODE":"INFO-200","MESSAGE":"..."}} —
 *    INFO-200(데이터 없음)은 빈 리스트, 그 외 CODE 는 throw.
 *  - DATA_VALUE 가 빈 문자열/"-" 인 row 는 skip (결측/휴장일).
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const BASE = 'https://ecos.bok.or.kr/api';
const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const ECOS_NO_DATA_CODE = 'INFO-200';
const REQUEST_TIMEOUT_MS = 20_000;
const STATISTIC_SEARCH_LIMIT = 10_000;
const KEY_STATISTIC_LIMIT = 100;

export const API_KEY = findEnvKey('ECOS_API_KEY', MODULE_DIR);

export function requireKey() {
  if (!API_KEY) {
    throw new Error('ECOS_API_KEY 가 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://ecos.bok.or.kr → Open API)');
  }
}

/**
 * 지표 카탈로그 — economics-service V1 시드와 동일 좌표 (검증된 코드).
 * cycle D 는 yyyyMMdd, M 은 yyyyMM 날짜 포맷을 쓴다.
 */
export const INDICATORS = {
  BASE_RATE:   { name: '한국은행 기준금리', unit: '%',        cycle: 'D', statCode: '722Y001', itemCode: '0101000' },
  TREASURY_3Y: { name: '국고채 3년 금리',   unit: '%',        cycle: 'D', statCode: '817Y002', itemCode: '010200000' },
  USD_KRW:     { name: '원/달러 환율',      unit: 'KRW',      cycle: 'D', statCode: '731Y001', itemCode: '0000001' },
  CPI:         { name: '소비자물가지수',    unit: '2020=100', cycle: 'M', statCode: '901Y009', itemCode: '0' },
};

async function getJson(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
  if (!res.ok) throw new Error(`ECOS → HTTP ${res.status}`);
  return res.json();
}

/**
 * StatisticSearch 원시 호출 — 임의의 통계/항목 코드 조회.
 * @returns {Array<{time: string, value: number}>}
 */
export async function statisticSearch({ statCode, itemCode, cycle, start, end }) {
  requireKey();
  const url = `${BASE}/StatisticSearch/${API_KEY}/json/kr/1/${STATISTIC_SEARCH_LIMIT}/${statCode}/${cycle}/${start}/${end}/${itemCode}`;
  const body = await getJson(url);

  const result = body?.RESULT ?? body?.StatisticSearch?.RESULT;
  if (result?.CODE) {
    if (result.CODE === ECOS_NO_DATA_CODE) return [];   // 조회 데이터 없음 — 에러 아님
    throw new Error(`ECOS 오류 ${result.CODE}: ${result.MESSAGE} (statCode=${statCode})`);
  }

  const rows = body?.StatisticSearch?.row ?? [];
  const out = [];
  for (const row of rows) {
    const raw = String(row.DATA_VALUE ?? '').trim();
    if (!raw || raw === '-') continue;           // 결측/휴장일 skip
    const value = Number(raw);
    if (Number.isNaN(value)) continue;
    out.push({ time: String(row.TIME), value });
  }
  return out;
}

const pad = n => String(n).padStart(2, '0');
function fmt(d, cycle) {
  const yearMonth = `${d.getFullYear()}${pad(d.getMonth() + 1)}`;
  return cycle === 'M' ? yearMonth : `${yearMonth}${pad(d.getDate())}`;
}

/**
 * 카탈로그 지표 조회 — 최근 monthsBack 개월 구간.
 * @returns {{code, name, unit, cycle, count, first, latest, changeFromFirst, observations}}
 */
export async function fetchIndicator(code, { monthsBack = 13, now = new Date() } = {}) {
  const ind = INDICATORS[code];
  if (!ind) throw new Error(`알 수 없는 지표: ${code} (사용 가능: ${Object.keys(INDICATORS).join(', ')})`);
  const from = new Date(now);
  from.setMonth(from.getMonth() - monthsBack);
  const observations = await statisticSearch({
    statCode: ind.statCode, itemCode: ind.itemCode, cycle: ind.cycle,
    start: fmt(from, ind.cycle), end: fmt(now, ind.cycle),
  });
  const first = observations[0] ?? null;
  const latest = observations[observations.length - 1] ?? null;
  return {
    code, name: ind.name, unit: ind.unit, cycle: ind.cycle,
    count: observations.length, first, latest,
    changeFromFirst: first && latest ? Number((latest.value - first.value).toFixed(4)) : null,
    observations,
  };
}

/**
 * 100대 통계지표 스냅숏 (KeyStatisticList) — 거시 환경 한 번에 훑기.
 * @returns {Array<{class: string, name: string, value: number|null, unit: string, time: string}>}
 */
export async function keyStatistics() {
  requireKey();
  const url = `${BASE}/KeyStatisticList/${API_KEY}/json/kr/1/${KEY_STATISTIC_LIMIT}`;
  const body = await getJson(url);
  const result = body?.RESULT ?? body?.KeyStatisticList?.RESULT;
  if (result?.CODE && result.CODE !== ECOS_NO_DATA_CODE) {
    throw new Error(`ECOS 오류 ${result.CODE}: ${result.MESSAGE}`);
  }
  const rows = body?.KeyStatisticList?.row ?? [];
  return rows.map(r => ({
    class: String(r.CLASS_NAME ?? ''),
    name: String(r.KEYSTAT_NAME ?? ''),
    value: r.DATA_VALUE != null && String(r.DATA_VALUE).trim() !== '' ? Number(r.DATA_VALUE) : null,
    unit: String(r.UNIT_NAME ?? ''),
    time: String(r.CYCLE ?? ''),
  }));
}
