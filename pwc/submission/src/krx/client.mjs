/**
 * 금융위 주식시세정보(KRX) 클라이언트 (data.go.kr, Node 18+, zero-dependency).
 *
 * getStockPriceInfo — 종목코드(6자리) 기준 일별 종가·거래량·시가총액을 기간 조회한다.
 * E6(시장·현금흐름 괴리)/E7(공시·주가 정합) 파생의 유일한 시세 입력이다.
 *
 * 인증: serviceKey 쿼리 파라미터 — env KRX_API_KEY (없으면 같은 포털 계정 키인
 * DATA_GO_KR_API_KEY 폴백, 둘 다 없으면 상위 .env 탐색은 findEnvKey 가 수행).
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const BASE = 'https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService';
const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const REQUEST_TIMEOUT_MS = 20_000;

export const API_KEY = findEnvKey('KRX_API_KEY', MODULE_DIR) || findEnvKey('DATA_GO_KR_API_KEY', MODULE_DIR);

export function requireKey() {
  if (!API_KEY) {
    throw new Error('KRX_API_KEY(또는 DATA_GO_KR_API_KEY) 가 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://www.data.go.kr)');
  }
}

const toNum = (v) => {
  const s = String(v ?? '').replaceAll(',', '').trim();
  if (!s) return null; // Number('') === 0 함정 — 결측은 0 이 아니라 null
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
};

/**
 * 일별 시세 조회 — basDt 오름차순으로 정규화해 반환한다.
 * @returns {Promise<Array<{ basDt: string, close: number|null, volume: number|null, marketCap: number|null }>>}
 */
export async function fetchDailyPrices({ stockCode, beginDate, endDate, rows = 200 }) {
  requireKey();
  const code = String(stockCode ?? '').trim();
  if (!/^\d{6}$/.test(code)) throw new Error(`종목코드는 6자리여야 합니다: "${stockCode}"`);
  const params = new URLSearchParams({
    serviceKey: API_KEY,
    resultType: 'json',
    numOfRows: String(rows),
    pageNo: '1',
    likeSrtnCd: code,
    beginBasDt: String(beginDate),
    endBasDt: String(endDate),
  });
  const res = await fetch(`${BASE}/getStockPriceInfo?${params}`, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`금융위 주식시세 API → HTTP ${res.status}`);
  const body = await res.json().catch(() => {
    // 인증키 오류 등은 XML 로 내려온다 — JSON 파싱 실패 자체가 신호.
    throw new Error('금융위 주식시세 응답 JSON 파싱 실패 (인증키 오류 시 XML 응답)');
  });
  const header = body?.response?.header;
  if (header && header.resultCode !== '00') {
    throw new Error(`금융위 주식시세 API 오류 resultCode=${header.resultCode} ${header.resultMsg ?? ''}`);
  }
  const items = body?.response?.body?.items?.item ?? [];
  return (Array.isArray(items) ? items : [items])
    .filter((it) => String(it?.srtnCd ?? '').trim() === code) // likeSrtnCd 는 부분 일치 — 정확 매칭만
    .map((it) => ({
      basDt: String(it.basDt ?? ''),
      close: toNum(it.clpr),
      volume: toNum(it.trqu),
      marketCap: toNum(it.mrktTotAmt),
    }))
    .filter((p) => /^\d{8}$/.test(p.basDt))
    .sort((a, b) => a.basDt.localeCompare(b.basDt));
}
