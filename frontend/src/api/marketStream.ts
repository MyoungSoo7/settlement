/**
 * market-stream-service (Go, 8110) — 실시간 시세 SSE 클라이언트.
 *
 * 경로 계약: 프론트는 항상 `/api/market-stream/stream/{stockCode}` 로 구독한다.
 *  - dev: vite proxy 가 프리픽스를 벗겨 8110 `/stream/{code}` 로 직결
 *  - docker/k8s: nginx → gateway 가 RewritePath 로 동일하게 전달
 * 서버는 1초 간격으로 `event: tick` 프레임(JSON)을 push 한다. axios 는 SSE 를
 * 다루지 못하므로 이 모듈만 EventSource 를 직접 쓴다.
 */

/** 서버 tick 페이로드 (market-stream internal/quote/quote.go Tick 과 계약) */
export interface QuoteTick {
  stockCode: string;
  price: number;
  ts: string; // RFC3339 (밀리초 정밀도)
}

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
