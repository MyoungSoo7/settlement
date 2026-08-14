import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  chargebackApi,
  CHARGEBACK_REASON_LABEL,
  CHARGEBACK_STATUS_LABEL,
  type Chargeback,
} from '@/api/chargeback';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const chargeback: Chargeback = {
  id: 5,
  paymentId: 42,
  settlementId: 55,
  amount: '30000',
  reasonCode: 'FRAUD',
  reasonDetail: '카드 도용 신고',
  status: 'OPEN',
  source: 'PG_WEBHOOK',
  pgChargebackId: 'cb_1',
  decidedBy: null,
  decisionNote: null,
  raisedAt: '2026-08-01T00:00:00Z',
  decidedAt: null,
};

describe('chargebackApi 조회', () => {
  beforeEach(() => vi.resetAllMocks());

  it('목록은 봉투(envelope)를 벗겨 분쟁만 돌려준다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [{ chargeback }] });

    const result = await chargebackApi.list('OPEN');

    expect(api.get).toHaveBeenCalledWith('/admin/chargebacks', {
      params: { status: 'OPEN', max: 20 },
    });
    expect(result[0].id).toBe(5);
  });

  it('조회 건수를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [] });

    await chargebackApi.list('ACCEPTED', 5);

    expect(api.get).toHaveBeenCalledWith('/admin/chargebacks', {
      params: { status: 'ACCEPTED', max: 5 },
    });
  });

  it('상세도 봉투를 벗긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { chargeback } });

    const result = await chargebackApi.get(5);

    expect(api.get).toHaveBeenCalledWith('/admin/chargebacks/5');
    expect(result.reasonCode).toBe('FRAUD');
  });
});

describe('chargebackApi 등록·판정', () => {
  beforeEach(() => vi.resetAllMocks());

  it('PG 통지 누락분을 수동 등록한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { chargeback } });

    const body = {
      paymentId: 42,
      settlementId: 55,
      amount: '30000',
      reasonCode: 'DUPLICATE' as const,
    };
    await chargebackApi.open(body);

    expect(api.post).toHaveBeenCalledWith('/admin/chargebacks', body);
  });

  it('수락은 멱등키를 붙인다 — 중복 차감은 되돌리기 어렵다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: { chargeback: { ...chargeback, status: 'ACCEPTED' } },
    });

    const result = await chargebackApi.accept(5, '증빙 불충분');

    const [url, body, config] = vi.mocked(api.post).mock.calls[0];
    expect(url).toBe('/admin/chargebacks/5/accept');
    expect(body).toEqual({ note: '증빙 불충분' });
    expect((config as { headers: Record<string, string> }).headers['Idempotency-Key']).toBeTruthy();
    expect(result.status).toBe('ACCEPTED');
  });

  it('기각도 멱등키를 붙이고 사유를 함께 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: { chargeback: { ...chargeback, status: 'REJECTED' } },
    });

    const result = await chargebackApi.reject(5, '배송 증빙 확인');

    const [url, body, config] = vi.mocked(api.post).mock.calls[0];
    expect(url).toBe('/admin/chargebacks/5/reject');
    expect(body).toEqual({ note: '배송 증빙 확인' });
    expect((config as { headers: Record<string, string> }).headers['Idempotency-Key']).toBeTruthy();
    expect(result.status).toBe('REJECTED');
  });

  it('연속 호출은 서로 다른 멱등키를 쓴다 (각 결정이 별개)', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { chargeback } });

    await chargebackApi.accept(5, 'a');
    await chargebackApi.accept(6, 'b');

    const keyOf = (i: number) =>
      (vi.mocked(api.post).mock.calls[i][2] as { headers: Record<string, string> }).headers[
        'Idempotency-Key'
      ];
    expect(keyOf(0)).not.toBe(keyOf(1));
  });
});

describe('표시 라벨', () => {
  it('사유·상태 라벨이 모든 코드값을 덮는다', () => {
    expect(Object.keys(CHARGEBACK_REASON_LABEL)).toHaveLength(5);
    expect(CHARGEBACK_REASON_LABEL.FRAUD).toBe('도용·사기');
    expect(CHARGEBACK_STATUS_LABEL.ACCEPTED).toBe('수락(셀러 부담)');
    expect(CHARGEBACK_STATUS_LABEL.REJECTED).toBe('기각(정산 영향 없음)');
  });
});
