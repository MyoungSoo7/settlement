import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { streamUrl, subscribeQuoteStream, type QuoteTick } from '@/api/marketStream';

/** jsdom 에는 EventSource 가 없으므로 리스너를 노출하는 페이크로 대체한다. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners: Record<string, Array<(e: MessageEvent) => void>> = {};

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    (this.listeners[type] ??= []).push(cb);
  }

  emit(type: string, data: string) {
    for (const cb of this.listeners[type] ?? []) {
      cb({ data } as MessageEvent);
    }
  }

  close() {
    this.closed = true;
  }
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('marketStream api', () => {
  it('streamUrl 은 게이트웨이 경로 계약(/api/market-stream/stream/{code})을 따른다', () => {
    expect(streamUrl('005930')).toBe('/api/market-stream/stream/005930');
  });

  it('tick 이벤트 페이로드를 QuoteTick 으로 파싱해 콜백에 전달한다', () => {
    const ticks: QuoteTick[] = [];
    subscribeQuoteStream('005930', (t) => ticks.push(t));

    const es = FakeEventSource.instances[0];
    expect(es.url).toBe('/api/market-stream/stream/005930');

    es.emit('tick', JSON.stringify({ stockCode: '005930', price: 71500.5, ts: '2026-07-17T00:00:01.000Z' }));
    expect(ticks).toHaveLength(1);
    expect(ticks[0].price).toBe(71500.5);
    expect(ticks[0].stockCode).toBe('005930');
  });

  it('계약 밖(비 JSON) 프레임은 무시하고 스트림을 유지한다', () => {
    const ticks: QuoteTick[] = [];
    subscribeQuoteStream('005930', (t) => ticks.push(t));

    const es = FakeEventSource.instances[0];
    es.emit('tick', 'not-json');
    es.emit('tick', JSON.stringify({ stockCode: '005930', price: 70000, ts: '2026-07-17T00:00:02.000Z' }));

    expect(ticks).toHaveLength(1);
    expect(ticks[0].price).toBe(70000);
  });

  it('상태 콜백은 connecting → open 순으로 통지되고 close() 가 소켓을 닫는다', () => {
    const states: string[] = [];
    const handle = subscribeQuoteStream('000660', () => undefined, (s) => states.push(s));

    const es = FakeEventSource.instances[0];
    expect(states).toEqual(['connecting']);
    es.onopen?.();
    expect(states).toEqual(['connecting', 'open']);

    handle.close();
    expect(es.closed).toBe(true);
  });
});
