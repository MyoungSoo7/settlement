/**
 * market-stream-service (Go, 8110) — 실시간 시세 SSE 클라이언트.
 *
 * 경로 계약: 프론트는 항상 `/api/market-stream/stream/{stockCode}` 로 구독한다.
 *  - dev: vite proxy 가 프리픽스를 벗겨 8110 `/stream/{code}` 로 직결
 *  - docker/k8s: nginx → gateway 가 RewritePath 로 동일하게 전달
 * 서버는 1초 간격으로 `event: tick` 프레임(JSON)을 push 한다. axios 는 SSE 를
 * 다루지 못하므로 이 모듈만 EventSource 를 직접 쓴다.
 */

/**
 * 값 출처 — 이 가격을 믿어도 되는가.
 * market-service REST 의 `source`(SAMPLE/EXCHANGE)와 같은 어휘를 쓴다.
 */
export type QuoteValueSource = 'SAMPLE' | 'EXCHANGE';

export const QUOTE_SOURCE_LABEL: Record<QuoteValueSource, string> = {
  SAMPLE: '근사 샘플',
  EXCHANGE: '거래소 실시세',
};

/** 서버 tick 페이로드 (market-stream internal/quote/quote.go Tick 과 계약) */
export interface QuoteTick {
  stockCode: string;
  price: number;
  ts: string; // RFC3339 (밀리초 정밀도)
  /**
   * 구버전 서버는 이 필드를 보내지 않는다. 없을 때는 신뢰할 수 없는 쪽(SAMPLE)으로
   * 취급한다 — 표기 누락이 곧 "실시세처럼 보임"이 되면 안 된다.
   */
  source?: QuoteValueSource;
}

/** 출처 미표기 프레임을 안전한 쪽으로 좁힌다. */
export const tickValueSource = (tick: Pick<QuoteTick, 'source'>): QuoteValueSource =>
  tick.source === 'EXCHANGE' ? 'EXCHANGE' : 'SAMPLE';

export type QuoteStreamState = 'connecting' | 'open' | 'error';

export interface QuoteStreamHandle {
  close: () => void;
}

const STREAM_PREFIX = '/api/market-stream';

export const streamUrl = (stockCode: string): string =>
  `${STREAM_PREFIX}/stream/${encodeURIComponent(stockCode)}`;

/**
 * 종목 실시간 시세 구독. 반환된 handle.close() 로 해제한다(컴포넌트 unmount 시 필수 —
 * 안 하면 서버 Hub 구독이 살아남아 백엔드 goroutine 이 계속 tick 을 만든다).
 * EventSource 는 끊기면 스스로 재연결하므로 error 상태는 일시적일 수 있다.
 */
export const subscribeQuoteStream = (
  stockCode: string,
  onTick: (tick: QuoteTick) => void,
  onStateChange?: (state: QuoteStreamState) => void,
): QuoteStreamHandle => {
  const es = new EventSource(streamUrl(stockCode));
  onStateChange?.('connecting');
  es.onopen = () => onStateChange?.('open');
  es.onerror = () => onStateChange?.('error');
  es.addEventListener('tick', (e) => {
    try {
      onTick(JSON.parse((e as MessageEvent).data) as QuoteTick);
    } catch {
      // 계약 밖 프레임은 무시 — 스트림은 유지한다.
    }
  });
  return { close: () => es.close() };
};
