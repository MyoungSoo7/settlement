import { describe, it, expect, vi, beforeEach } from 'vitest';
import { depositAdminApi } from '@/api/deposit';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 운영 콘솔 API 계약.
 *
 * <p>고정하는 것 셋. ① 멱등 키(referenceType·referenceId·offsetSequence)를 <b>본문에 그대로</b>
 * 실어 보낸다 — 클라이언트가 가공하면 원장 L3 UNIQUE 가 겨냥하는 값이 달라진다.
 * ② 입금과 출금은 <b>다른 URL</b>이다(방향을 본문 플래그로 넘기면 오조작이 조용해진다).
 * ③ 해소는 실제 차감액을 돌려준다 — 화면이 "얼마 나갔는지" 말할 수 있어야 한다.
 */
describe('depositAdminApi — 잔고 조작', () => {
  it('입금·출금은 서로 다른 경로이고 멱등 키를 그대로 싣는다', async () => {
    mocked.post.mockResolvedValue({ data: null } as never);
    const input = { amount: 5000, referenceId: 'TICKET-1', referenceType: 'MANUAL_ADJUSTMENT' };

    await depositAdminApi.credit(7, input);
    expect(mocked.post).toHaveBeenCalledWith('/admin/deposits/accounts/7/credits', input);

    await depositAdminApi.debit(7, input);
    expect(mocked.post).toHaveBeenCalledWith('/admin/deposits/accounts/7/debits', input);
  });

  it('선점은 만료를 생략할 수 있다 — 서버 기본값(72시간)에 맡긴다', async () => {
    mocked.post.mockResolvedValue({ data: { id: 3, remainingAmount: 5000 } } as never);

    await depositAdminApi.placeHold(7, {
      holderType: 'CARD_AUTHORIZATION', holderReference: 'AUTH-1', amount: 5000,
    });

    const [, body] = mocked.post.mock.calls[0];
    expect(body).not.toHaveProperty('expiresAt');
  });

  it('상계는 분할 회차를 그대로 보낸다 — 원장 UNIQUE 의 마지막 칸이다', async () => {
    mocked.post.mockResolvedValue({ data: null } as never);

    await depositAdminApi.applyOffset(7, {
      holderType: 'CARD_AUTHORIZATION', holderReference: 'AUTH-1',
      offsetAmount: 3000, offsetSequence: 2,
    });

    expect(mocked.post).toHaveBeenCalledWith('/admin/deposits/accounts/7/offsets',
      expect.objectContaining({ offsetSequence: 2 }));
  });
});

describe('depositAdminApi — 부족분', () => {
  it('해소는 실제 차감액을 돌려준다', async () => {
    mocked.post.mockResolvedValue({ data: { appliedAmount: 2500 } } as never);

    await expect(depositAdminApi.resolveShortfall(9)).resolves.toBe(2500);
    expect(mocked.post).toHaveBeenCalledWith('/admin/deposits/shortfalls/9/resolve');
  });

  it('상각은 별도 경로다 — 해소와 같은 버튼으로 섞이면 안 된다', async () => {
    mocked.post.mockResolvedValue({ data: null } as never);

    await depositAdminApi.writeOffShortfall(9);
    expect(mocked.post).toHaveBeenCalledWith('/admin/deposits/shortfalls/9/write-off');
  });

  it('미해소 목록을 그대로 돌려준다', async () => {
    mocked.get.mockResolvedValue({ data: [{ id: 1, shortfallAmount: 500 }] } as never);

    const rows = await depositAdminApi.openShortfalls();
    expect(mocked.get).toHaveBeenCalledWith('/admin/deposits/shortfalls');
    expect(rows).toHaveLength(1);
  });
});
