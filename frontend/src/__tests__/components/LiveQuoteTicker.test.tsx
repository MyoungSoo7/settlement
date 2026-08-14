import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import LiveQuoteTicker from '@/components/LiveQuoteTicker';
import { subscribeQuoteStream, type QuoteStreamState, type QuoteTick } from '@/api/marketStream';

vi.mock('@/api/marketStream', () => ({
  subscribeQuoteStream: vi.fn(),
}));

let pushTick: ((t: QuoteTick) => void) | null = null;
let pushState: ((s: QuoteStreamState) => void) | null = null;
const close = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  pushTick = null;
  pushState = null;
  vi.mocked(subscribeQuoteStream).mockImplementation((_code, onTick, onState) => {
    pushTick = onTick;
    pushState = onState as (s: QuoteStreamState) => void;
    return { close };
  });
});

const tick = (price: number): QuoteTick => ({
  stockCode: '005930',
  price,
  ts: '2026-08-14T01:00:00.000Z',
});

describe('LiveQuoteTicker', () => {
  it('첫 틱 전에는 대기 문구와 연결 중 배지를 보여 준다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);

    expect(screen.getByText('첫 틱 대기 중…')).toBeInTheDocument();
    expect(screen.getByText('연결 중')).toBeInTheDocument();
    expect(screen.getByText('005930')).toBeInTheDocument();
  });

  it('종목명을 주면 코드와 함께 보여 준다', () => {
    render(<LiveQuoteTicker stockCode="005930" name="삼성전자" />);

    expect(screen.getByText(/삼성전자/)).toBeInTheDocument();
  });

  it('LIVE 상태 배지로 바뀐다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushState?.('open'));

    expect(screen.getByText('LIVE')).toBeInTheDocument();
  });

  it('오류 상태는 재연결 중으로 표시한다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushState?.('error'));

    expect(screen.getByText('재연결 중')).toBeInTheDocument();
  });

  it('첫 틱은 방향 표시 없이 가격만 그린다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushTick?.(tick(71500)));

    expect(screen.getByText('71,500')).toBeInTheDocument();
    expect(screen.queryByText('▲')).not.toBeInTheDocument();
    expect(screen.queryByText('▼')).not.toBeInTheDocument();
  });

  it('직전 틱보다 오르면 빨강·▲ (국내 시세 관례)', () => {
    const { container } = render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushTick?.(tick(71500)));
    act(() => pushTick?.(tick(72000)));

    expect(screen.getByText('▲')).toBeInTheDocument();
    expect(container.querySelector('.text-red-600')).not.toBeNull();
  });

  it('내리면 파랑·▼', () => {
    const { container } = render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushTick?.(tick(71500)));
    act(() => pushTick?.(tick(71000)));

    expect(screen.getByText('▼')).toBeInTheDocument();
    expect(container.querySelector('.text-blue-600')).not.toBeNull();
  });

  it('같은 가격이면 방향 표시가 없다', () => {
    render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushTick?.(tick(71500)));
    act(() => pushTick?.(tick(71500)));

    expect(screen.queryByText('▲')).not.toBeInTheDocument();
    expect(screen.queryByText('▼')).not.toBeInTheDocument();
  });

  it('언마운트하면 스트림을 닫는다', () => {
    const { unmount } = render(<LiveQuoteTicker stockCode="005930" />);

    unmount();

    expect(close).toHaveBeenCalledTimes(1);
  });

  it('종목이 바뀌면 이전 스트림을 닫고 새로 구독한다', () => {
    const { rerender } = render(<LiveQuoteTicker stockCode="005930" />);

    act(() => pushTick?.(tick(71500)));
    rerender(<LiveQuoteTicker stockCode="000660" />);

    expect(close).toHaveBeenCalledTimes(1);
    expect(subscribeQuoteStream).toHaveBeenLastCalledWith(
      '000660',
      expect.any(Function),
      expect.any(Function),
    );
    expect(screen.getByText('첫 틱 대기 중…')).toBeInTheDocument();
  });
});
