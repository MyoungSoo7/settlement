import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  payoutApi,
  newIdempotencyKey,
  PAYOUT_STATUS_LABEL,
  type Payout,
  type PayoutStatus,
} from '@/api/payout';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const payout: Payout = {
  id: 11,
  settlementId: 500,
  sellerId: 7,
  amount: 1_250_000,
  status: 'FAILED',
  bank: '004',
  account: '1234****89',
  holder: '홍길동',
  firmBankingTxnId: null,
  failureReason: '예금주 불일치',
  retryCount: 1,
  operatorId: 'admin@example.com',
  requestedAt: '2026-08-09T09:00:00',
  sentAt: null,
  completedAt: null,
  failedAt: '2026-08-09T09:05:00',
};

describe('payoutApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /** 서버가 배열 원소마다 { payout: {...} } 로 감싸 준다. 껍데기는 이 모듈에서 끝나야 한다. */
  it('실패 목록은 응답 껍데기를 벗겨 배열로 돌려준다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [{ payout }, { payout: { ...payout, id: 12 } }] });

    const result = await payoutApi.listFailed(50);

    expect(api.get).toHaveBeenCalledWith('/admin/payouts/failed', { params: { limit: 50 } });
    expect(result).toHaveLength(2);
    expect(result[0].id).toBe(11);
    expect(result[0].failureReason).toBe('예금주 불일치');
  });

  it('대기 목록도 같은 방식이며 기본 limit 은 20 이다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await payoutApi.listPending();

    expect(api.get).toHaveBeenCalledWith('/admin/payouts/pending', { params: { limit: 20 } });
  });

  it('상세 조회도 껍데기를 벗긴다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { payout } });

    const result = await payoutApi.get(11);

    expect(api.get).toHaveBeenCalledWith('/admin/payouts/11');
    expect(result.settlementId).toBe(500);
  });

  /**
   * 되돌리기 어려운 외부 송금이다. 키가 없으면 서버가 멱등을 적용하지 않아
   * 더블클릭 한 번이 두 번의 재시도가 된다 — 이 헤더가 있어야 409 로 막힌다.
   */
  it('재시도는 Idempotency-Key 를 반드시 실어 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { payout: { ...payout, status: 'REQUESTED' } } });

    await payoutApi.retry(11, 'key-abc');

    expect(api.post).toHaveBeenCalledWith('/admin/payouts/11/retry', null, {
      headers: { 'Idempotency-Key': 'key-abc' },
    });
  });

  it('취소는 사유와 Idempotency-Key 를 함께 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { payout: { ...payout, status: 'CANCELED' } } });

    await payoutApi.cancel(11, '계좌 폐쇄', 'key-cancel');

    expect(api.post).toHaveBeenCalledWith(
      '/admin/payouts/11/cancel',
      { reason: '계좌 폐쇄' },
      { headers: { 'Idempotency-Key': 'key-cancel' } }
    );
  });

  it('반송 기록은 재지급 결과를 그대로 돌려준다 (껍데기 없음)', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { bouncedPayoutId: 11, reason: '예금주 불일치', reissuedPayoutId: 12 },
    });

    const result = await payoutApi.bounce(11, '예금주 불일치', 'key-bounce');

    expect(api.post).toHaveBeenCalledWith(
      '/admin/payouts/11/bounce',
      { reason: '예금주 불일치' },
      { headers: { 'Idempotency-Key': 'key-bounce' } }
    );
    expect(result.reissuedPayoutId).toBe(12);
  });

  /** 미리보기는 아무것도 바꾸지 않는다 — 멱등 키가 붙을 이유가 없다(GET). */
  it('미리보기는 GET 이고 멱등 키를 쓰지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: { sendableCount: 3, sendableAmount: 300, limitedCount: 1, limitedAmount: 100, lines: [] },
    });

    const result = await payoutApi.preview();

    expect(api.get).toHaveBeenCalledWith('/admin/payouts/preview');
    expect(api.post).not.toHaveBeenCalled();
    expect(result.sendableCount).toBe(3);
  });

  it('즉시 실행은 집행 결과 요약을 돌려준다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { succeeded: 5, failed: 1, limitedSkipped: 2 } });

    const result = await payoutApi.executeNow();

    expect(api.post).toHaveBeenCalledWith('/admin/payouts/execute-now');
    expect(result).toEqual({ succeeded: 5, failed: 1, limitedSkipped: 2 });
  });
});

describe('newIdempotencyKey', () => {
  it('호출마다 다른 키를 만든다 — 조작 1회 = 키 1개', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(50);
  });

  it('빈 문자열을 만들지 않는다 — 빈 키는 서버에서 멱등 미적용이다', () => {
    expect(newIdempotencyKey().length).toBeGreaterThan(0);
  });
});

describe('PAYOUT_STATUS_LABEL', () => {
  it('모든 상태에 한글 라벨이 있다', () => {
    const all: PayoutStatus[] = ['REQUESTED', 'SENDING', 'COMPLETED', 'FAILED', 'CANCELED'];
    all.forEach((s) => {
      expect(PAYOUT_STATUS_LABEL[s]).toBeTruthy();
      expect(PAYOUT_STATUS_LABEL[s]).not.toBe(s);
    });
  });
});
