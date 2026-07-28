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

// 429/5xx 재시도 — Yahoo 는 무인증 공개 엔드포인트라 연속 조회(backtest 66종목 등)에서
// 속도 제한이 실제로 발생한다. 404 류(없는 심볼)는 재시도해도 소용없으므로 즉시 던진다.
const RETRY_STATUS = new Set([429, 500, 502, 503, 504]);
const RETRY_DELAYS_MS = [500, 1500];

// 같은 프로세스 안의 반복 질의(MCP 세션·리포트 루프)가 매번 Yahoo 를 치지 않도록
// 짧은 TTL 메모리 캐시. 60초 — 지연 시세 데모 어댑터에서 신선도 손실 없음.
const CACHE_TTL_MS = 60_000;
const chartCache = new Map(); // key → { at, result }

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function normalizeStockCode(stockCode) {
  const code = String(stockCode ?? '').trim();
  if (!/^\d{6}$/.test(code)) {
    throw new Error(`stockCode 는 6자리 숫자여야 합니다 (예: 005930) — got "${code}"`);
  }
  return code;
}

async function fetchChartOnce(symbol, { range, interval }) {
  const url = `${BASE}/${symbol}?range=${range}&interval=${interval}`;
  const res = await fetch(url, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { 'User-Agent': UA },
  });
  const body = await res.json().catch(() => null);
  const result = body?.chart?.result?.[0];
  if (!res.ok || !result?.meta?.regularMarketPrice) {
    const err = body?.chart?.error?.description ?? `HTTP ${res.status}`;
    const error = new Error(`chart ${symbol} → ${err}`);
    error.httpStatus = res.status;
    throw error;
  }
  return result;
}

async function fetchChart(symbol, { range = '5d', interval = '1d' } = {}) {
  const cacheKey = `${symbol}|${range}|${interval}`;
  const cached = chartCache.get(cacheKey);
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.result;

  let lastError;
  for (let attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      const result = await fetchChartOnce(symbol, { range, interval });
      // 만료 엔트리 청소 + 하드 상한 — 장수명 MCP 프로세스에서 캐시가 무한 증가하지 않도록
      if (chartCache.size >= 128) {
        for (const [key, entry] of chartCache) {
          if (Date.now() - entry.at >= CACHE_TTL_MS) chartCache.delete(key);
        }
        while (chartCache.size >= 256) {
          chartCache.delete(chartCache.keys().next().value);   // Map 삽입순 — 가장 오래된 것부터
        }
      }
      chartCache.set(cacheKey, { at: Date.now(), result });
      return result;
    } catch (error) {
      lastError = error;
      const retryable = RETRY_STATUS.has(error.httpStatus) || error.name === 'TimeoutError';
      if (!retryable || attempt === RETRY_DELAYS_MS.length) throw error;
      await sleep(RETRY_DELAYS_MS[attempt]);
    }
  }
  throw lastError;
}

/** 테스트/장시간 배치용 — 캐시 강제 초기화 */
export function clearChartCache() {
  chartCache.clear();
}

const FRESH_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;

/** 후보들 중 신선한(최근 14일 내 체결) 것을 우선 선택. 둘 다 낡았으면 더 최근 것 + stale 표시.
 *  (Yahoo 에는 코스닥 종목의 낡은 .KS 잔재 심볼이 남아 있어 — 예: 058470.KS 가 2024년
 *  가격을 반환 — 신선도 검증 없이 .KS 우선으로 잡으면 초보자에게 틀린 가격이 나간다.) */
export function pickFreshest(candidates, nowMs) {
  if (candidates.length === 0) return null;
  const withTime = candidates.map((c) => ({
    ...c,
    marketTimeMs: (c.result.meta?.regularMarketTime ?? 0) * 1000,
  }));
  const fresh = withTime.filter((c) => nowMs - c.marketTimeMs <= FRESH_WINDOW_MS);
  if (fresh.length > 0) return { ...fresh[0], stale: false };
  withTime.sort((a, b) => b.marketTimeMs - a.marketTimeMs);
  return { ...withTime[0], stale: true };
}

/** 6자리 코드 → .KS/.KQ 자동 해석. 신선도 검증 후 result 와 시장 라벨을 반환.
 *  KS 가 성공하고 신선하면 KQ 는 조회하지 않는다 — 대부분(KOSPI 종목)의 호출량을
 *  절반으로 줄여 공개 엔드포인트 속도 제한을 피한다. KS 가 낡았을 때만(코스닥
 *  종목의 .KS 잔재 심볼 케이스) KQ 를 추가 확인해 pickFreshest 로 고른다. */
async function resolveChart(stockCode, opts) {
  const code = normalizeStockCode(stockCode);
  const candidates = [];
  let lastError;
  for (const suffix of ['KS', 'KQ']) {
    try {
      const result = await fetchChart(`${code}.${suffix}`, opts);
      candidates.push({ result, market: MARKET_BY_SUFFIX[suffix], symbol: `${code}.${suffix}` });
      const marketTimeMs = (result.meta?.regularMarketTime ?? 0) * 1000;
      if (Date.now() - marketTimeMs <= FRESH_WINDOW_MS) break; // 신선 — 다음 접미사 조회 불필요
    } catch (error) {
      lastError = error;
    }
  }
  const picked = pickFreshest(candidates, Date.now());
  if (!picked) {
    throw new Error(`시세를 찾지 못했습니다 (KOSPI/KOSDAQ 모두 실패): ${lastError?.message}`);
  }
  return picked;
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

/**
 * 단기 거래정지 의심 판정 — 신선도 창(14일)으로는 못 잡는 케이스 보완.
 * 최근 유효 행(종가 존재) 2개의 거래량이 모두 0 **이고 종가도 동결**이어야 의심 신호
 * (AND 결합 — 저유동 소형주의 우발적 무거래일을 정지로 오탐하지 않기 위한 특이도 확보).
 * 확정이 아니다 — DART 공시의 거래정지 여부로 교차 확인하라고 안내한다.
 */
export function detectSuspectedHalt(result) {
  const closes = result.indicators?.quote?.[0]?.close ?? [];
  const volumes = result.indicators?.quote?.[0]?.volume ?? [];
  const recent = [];
  for (let i = closes.length - 1; i >= 0 && recent.length < 2; i -= 1) {
    if (closes[i] === null || closes[i] === undefined) continue;
    recent.push({ close: closes[i], volume: volumes[i] });
  }
  if (recent.length < 2) return false;
  const volumesZero = recent.every((r) => r.volume === 0);
  const priceFrozen = recent[0].close === recent[1].close;
  return volumesZero && priceFrozen;
}

export async function quote(stockCode) {
  const { result, market, symbol, stale } = await resolveChart(stockCode, { range: '5d', interval: '1d' });
  const meta = result.meta;
  const price = meta.regularMarketPrice;
  const prevClose = meta.chartPreviousClose || null;
  const suspectedHalt = detectSuspectedHalt(result);
  return {
    ...(stale ? { staleWarning: '최근 14일 내 체결이 없는 낡은 시세입니다 — 거래정지 여부를 확인하고 이 가격으로 계획을 세우지 마세요' } : {}),
    ...(suspectedHalt ? { haltWarning: '최근 거래일들의 거래량이 0 입니다 — 거래정지 가능성이 있으니 DART 공시(dart_disclosures)로 확인 전에는 이 가격으로 계획을 세우지 마세요' } : {}),
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
    asOf: new Date((meta.regularMarketTime ?? 0) * 1000).toISOString(),         // 시세 기준시각 (장중시각)
    retrievedAt: new Date().toISOString(),                                       // 수집시각 — 4개 MCP 서버 공통 키
    source: 'Yahoo Finance chart API (지연 시세 가능 — 데모용 공개 어댑터)',
  };
}

export async function history(stockCode, { days = 20 } = {}) {
  const n = Math.max(2, Math.min(Number(days) || 20, 400));
  const range = n <= 20 ? '1mo' : n <= 120 ? '6mo' : '2y';
  const { result, market, symbol } = await resolveChart(stockCode, { range, interval: '1d' });
  const rows = extractCloses(result).slice(-n);
  return {
    stockCode: normalizeStockCode(stockCode),
    symbol,
    market,
    days: rows.length,
    closes: rows,
    streak: consecutiveStreak(rows),
    retrievedAt: new Date().toISOString(),
    source: 'Yahoo Finance chart API (지연 시세 가능 — 데모용 공개 어댑터)',
  };
}

/** 백테스트용 장기 일별 종가 (기본 10년). 반환: { stockCode, market, closes: [{date, close}] } */
export async function dailyCloses(stockCode, { range = '10y' } = {}) {
  const { result, market, symbol } = await resolveChart(stockCode, { range, interval: '1d' });
  return {
    stockCode: normalizeStockCode(stockCode),
    symbol,
    market,
    closes: extractCloses(result),
  };
}
