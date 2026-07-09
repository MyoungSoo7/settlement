/**
 * 시세 조회 클라이언트 (zero-dependency, Node 18+) — 데모용 공개 시세 어댑터.
 *
 * 소스: Yahoo Finance chart API (키 불필요, 지연 시세 가능). KRX 6자리 종목코드를
 * KOSPI(.KS) → KOSDAQ(.KQ) 순으로 해석한다. DART `dart_corp_search` 가 주는
 * stockCode 와 그대로 조인된다 (기업명 → stockCode → 시세).
 *
 * ★ 어댑터 경계: 실서비스에서는 이 파일 하나만 증권사 사내 시세 API 로 교체하면
 * 된다 — MCP 도구 계약(price_quote/price_history)은 그대로 유지된다.
 * 반환값에는 항상 source 와 asOf 를 박아 "언제 어디 데이터인지"를 강제한다.
 */

const BASE = 'https://query1.finance.yahoo.com/v8/finance/chart';
const REQUEST_TIMEOUT_MS = 20_000;
const UA = 'Mozilla/5.0 (invest-companion demo)';

const MARKET_BY_SUFFIX = { KS: 'KOSPI', KQ: 'KOSDAQ' };

export function normalizeStockCode(stockCode) {
  const code = String(stockCode ?? '').trim();
  if (!/^\d{6}$/.test(code)) {
    throw new Error(`stockCode 는 6자리 숫자여야 합니다 (예: 005930) — got "${code}"`);
  }
  return code;
}

async function fetchChart(symbol, { range = '5d', interval = '1d' } = {}) {
  const url = `${BASE}/${symbol}?range=${range}&interval=${interval}`;
  const res = await fetch(url, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { 'User-Agent': UA },
  });
  const body = await res.json().catch(() => null);
  const result = body?.chart?.result?.[0];
  if (!res.ok || !result?.meta?.regularMarketPrice) {
    const err = body?.chart?.error?.description ?? `HTTP ${res.status}`;
    throw new Error(`chart ${symbol} → ${err}`);
  }
  return result;
}

/** 6자리 코드 → .KS/.KQ 자동 해석. 성공한 result 와 시장 라벨을 반환. */
async function resolveChart(stockCode, opts) {
  const code = normalizeStockCode(stockCode);
  let lastError;
  for (const suffix of ['KS', 'KQ']) {
    try {
      const result = await fetchChart(`${code}.${suffix}`, opts);
      return { result, market: MARKET_BY_SUFFIX[suffix], symbol: `${code}.${suffix}` };
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error(`시세를 찾지 못했습니다 (KOSPI/KOSDAQ 모두 실패): ${lastError?.message}`);
}

function toDateString(unixSeconds) {
  return new Date(unixSeconds * 1000).toISOString().slice(0, 10);
}

function extractCloses(result) {
  const timestamps = result.timestamp ?? [];
  const closes = result.indicators?.quote?.[0]?.close ?? [];
  const rows = [];
  for (let i = 0; i < timestamps.length; i += 1) {
    const close = closes[i];
    if (close === null || close === undefined) continue;
    rows.push({ date: toDateString(timestamps[i]), close: Math.round(close) });
  }
  return rows;
}

/** 마지막 값 기준 연속 상승/하락 일수 (당일 포함, 종가 기준). */
export function consecutiveStreak(rows) {
  if (rows.length < 2) return { direction: 'flat', days: 0 };
  let up = 0;
  let down = 0;
  for (let i = rows.length - 1; i > 0; i -= 1) {
    const diff = rows[i].close - rows[i - 1].close;
    if (diff > 0 && down === 0) up += 1;
    else if (diff < 0 && up === 0) down += 1;
    else break;
  }
  if (up > 0) return { direction: 'up', days: up };
  if (down > 0) return { direction: 'down', days: down };
  return { direction: 'flat', days: 0 };
}

export async function quote(stockCode) {
  const { result, market, symbol } = await resolveChart(stockCode, { range: '5d', interval: '1d' });
  const meta = result.meta;
  const price = meta.regularMarketPrice;
  const prevClose = meta.chartPreviousClose || null;
  return {
    stockCode: normalizeStockCode(stockCode),
    symbol,
    market,
    name: meta.longName || meta.shortName || null,
    price,
    prevClose,
    changePct: prevClose ? Number((((price - prevClose) / prevClose) * 100).toFixed(2)) : null,
    currency: meta.currency,
    fiftyTwoWeekHigh: meta.fiftyTwoWeekHigh || null,
    fiftyTwoWeekLow: meta.fiftyTwoWeekLow || null,
    asOf: new Date((meta.regularMarketTime ?? 0) * 1000).toISOString(),
    source: 'Yahoo Finance chart API (지연 시세 가능 — 데모용 공개 어댑터)',
  };
}

export async function history(stockCode, { days = 20 } = {}) {
  const n = Math.max(2, Math.min(Number(days) || 20, 90));
  const range = n <= 20 ? '1mo' : '6mo';
  const { result, market, symbol } = await resolveChart(stockCode, { range, interval: '1d' });
  const rows = extractCloses(result).slice(-n);
  return {
    stockCode: normalizeStockCode(stockCode),
    symbol,
    market,
    days: rows.length,
    closes: rows,
    streak: consecutiveStreak(rows),
    source: 'Yahoo Finance chart API (지연 시세 가능 — 데모용 공개 어댑터)',
  };
}
