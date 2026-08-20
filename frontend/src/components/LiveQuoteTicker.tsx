import React, { useEffect, useRef, useState } from 'react';
import {
  QUOTE_SOURCE_LABEL,
  subscribeQuoteStream,
  tickValueSource,
  type QuoteStreamState,
  type QuoteTick,
} from '@/api/marketStream';
import Card from '@/components/Card';

/**
 * 실시간 시세 티커 — market-stream-service SSE 를 구독해 현재가를 1초 간격으로 갱신한다.
 * 직전 틱 대비 등락에 따라 가격을 상승(빨강)/하락(파랑) 색으로 표시한다(국내 시세 관례).
 *
 * 'LIVE' 배지는 SSE 연결 상태일 뿐 값의 진위가 아니다. 시세 화면에서 그 둘은 쉽게 뒤섞여
 * 읽히므로, 틱이 실어 오는 값 출처를 따로 표기한다 — 현재 서버가 내보내는 틱은 랜덤워크
 * 합성값(SAMPLE)이다.
 */
const stateBadge: Record<QuoteStreamState, { label: string; cls: string }> = {
  connecting: { label: '연결 중', cls: 'bg-slate-100 text-slate-500' },
  open: { label: 'LIVE', cls: 'bg-emerald-100 text-emerald-700' },
  error: { label: '재연결 중', cls: 'bg-amber-100 text-amber-700' },
};

const fmtPrice = (v: number) =>
  v.toLocaleString('ko-KR', { maximumFractionDigits: 2 });

const fmtTime = (ts: string) => {
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('ko-KR');
};

interface Props {
  stockCode: string;
  /** 종목명 라벨(선택) — 없으면 코드만 표시 */
  name?: string;
}

const LiveQuoteTicker: React.FC<Props> = ({ stockCode, name }) => {
  const [tick, setTick] = useState<QuoteTick | null>(null);
  const [state, setState] = useState<QuoteStreamState>('connecting');
  const prevPriceRef = useRef<number | null>(null);
  const [direction, setDirection] = useState<'up' | 'down' | 'flat'>('flat');

  useEffect(() => {
    setTick(null);
    prevPriceRef.current = null;
    setDirection('flat');

    const handle = subscribeQuoteStream(
      stockCode,
      (t) => {
        const prev = prevPriceRef.current;
        if (prev !== null) {
          setDirection(t.price > prev ? 'up' : t.price < prev ? 'down' : 'flat');
        }
        prevPriceRef.current = t.price;
        setTick(t);
      },
      setState,
    );
    return () => handle.close();
  }, [stockCode]);

  const priceCls =
    direction === 'up'
      ? 'text-red-600'
      : direction === 'down'
        ? 'text-blue-600'
        : 'text-slate-900';
  const arrow = direction === 'up' ? '▲' : direction === 'down' ? '▼' : '';
  const badge = stateBadge[state];
  const valueSource = tick ? tickValueSource(tick) : null;
  const isSample = valueSource === 'SAMPLE';

  return (
    <Card>
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-slate-600">실시간 시세</span>
            <span className={`rounded-full px-2 py-0.5 text-[11px] font-bold ${badge.cls}`}>
              {badge.label}
            </span>
            {isSample && (
              <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-bold text-amber-700">
                {QUOTE_SOURCE_LABEL.SAMPLE}
              </span>
            )}
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {name ? `${name} ` : ''}
            <span className="font-mono">{stockCode}</span>
            {tick && ` · ${fmtTime(tick.ts)}`}
          </p>
        </div>
        <div className="text-right">
          {tick ? (
            <span className={`text-2xl font-bold tabular-nums ${priceCls}`}>
              {arrow && <span className="mr-1 text-base align-middle">{arrow}</span>}
              {fmtPrice(tick.price)}
              <span className="ml-1 text-sm font-medium text-slate-400">원</span>
            </span>
          ) : (
            <span className="text-sm text-slate-400">첫 틱 대기 중…</span>
          )}
        </div>
      </div>
      {isSample && (
        <p className="mt-2 text-[11px] text-amber-700">
          실 시세 피드 연동 전이라 <strong>모의 생성값</strong>입니다 — 투자 판단·계산에 쓰지 마세요.
        </p>
      )}
    </Card>
  );
};

export default LiveQuoteTicker;
