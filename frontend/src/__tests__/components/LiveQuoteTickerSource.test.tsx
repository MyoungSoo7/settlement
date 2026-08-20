import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import LiveQuoteTicker from '@/components/LiveQuoteTicker';
import { subscribeQuoteStream, type QuoteStreamState, type QuoteTick } from '@/api/marketStream';

// 구독 함수만 갈아끼우고 나머지(출처 상수·정규화 헬퍼)는 실제 구현을 쓴다.
// 전체를 통째로 대체하면 컴포넌트와 테스트가 서로 다른 출처 규칙을 보게 된다.
vi.mock('@/api/marketStream', async (importActual) => ({
  ...(await importActual<typeof import('@/api/marketStream')>()),
  subscribeQuoteStream: vi.fn(),
}));

let pushTick: ((t: QuoteTick) => void) | null = null;
const close = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  pushTick = null;
  vi.mocked(subscribeQuoteStream).mockImplementation((_code, onTick, onState) => {
    pushTick = onTick;
    (onState as (s: QuoteStreamState) => void)('open');
    return { close };
  });
});

const tick = (source: QuoteTick['source']): QuoteTick => ({
  stockCode: '005930',
  price: 71234.5,
  ts: '2026-08-14T01:00:00.000Z',
  source,
});

/**
 * 화면에 뜬 숫자가 실시세인지 근사 샘플인지 보는 사람이 알 수 있어야 한다.
 *
 * market-stream 의 틱은 랜덤워크 합성값인데 티커에는 'LIVE' 배지만 떴다. LIVE 는 SSE 연결
 * 상태를 뜻하지만 시세 화면에서는 "실시간 실시세"로 읽힌다 — 조회 화면(EconomicsPage 등)이
 * SEED/실데이터를 구분해 표기해 온 것과도 어긋난다.
 */
describe('LiveQuoteTicker 값 출처 표기', () => {
  it('SAMPLE 틱에는 근사 샘플 배지를 띄운다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);
    act(() => pushTick!(tick('SAMPLE')));

    expect(screen.getByText('근사 샘플')).toBeInTheDocument();
  });

  it('SAMPLE 틱에는 계산에 쓰지 말라는 경고를 함께 보여 준다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);
    act(() => pushTick!(tick('SAMPLE')));

    expect(screen.getByText(/모의 생성값/)).toBeInTheDocument();
  });

  it('EXCHANGE 틱에는 샘플 배지도 경고도 붙이지 않는다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);
    act(() => pushTick!(tick('EXCHANGE')));

    expect(screen.queryByText('근사 샘플')).not.toBeInTheDocument();
    expect(screen.queryByText(/모의 생성값/)).not.toBeInTheDocument();
  });

  it('출처가 없는 구버전 프레임은 신뢰할 수 없는 쪽으로 취급한다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);
    act(() => pushTick!({ stockCode: '005930', price: 100, ts: '2026-08-14T01:00:00.000Z' } as QuoteTick));

    // 표기 누락이 곧 "실시세처럼 보임"이 되면 안 된다 — 모르면 샘플로 본다.
    expect(screen.getByText('근사 샘플')).toBeInTheDocument();
  });
});
