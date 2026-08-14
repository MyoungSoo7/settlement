import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from '@/api/axios';
import { cardApi } from '@/api/card';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const mockedGet = vi.mocked(api.get);
const mockedPost = vi.mocked(api.post);
const mockedPatch = vi.mocked(api.patch);

/**
 * 법인카드 API 모듈 — gateway 가 card-service 로 라우팅하는 `/api/cards/**` 경로 계약을 고정한다.
 * 요청자(userId)는 어디에도 싣지 않는다 — JWT 주체에서만 파생되는 것이 IDOR 방어의 핵심이라,
 * 본문에 사용자 식별자가 섞이는 순간 서버가 막아도 프론트가 오용을 유도하는 모양이 된다.
 */
describe('cardApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedGet.mockResolvedValue({ data: {} });
    mockedPost.mockResolvedValue({ data: {} });
    mockedPatch.mockResolvedValue({ data: {} });
  });

  it('계정 개설은 organizationId 만 보낸다 — 요청자는 JWT 에서 온다', async () => {
    await cardApi.openAccount(7);

    expect(mockedPost).toHaveBeenCalledWith('/api/cards/accounts', { organizationId: 7 });
  });

  it('계정 조회·계정 카드 목록은 계정 경로로 읽는다', async () => {
    mockedGet.mockResolvedValue({ data: [] });
    await cardApi.getAccount(3);
    await cardApi.listCards(3);

    expect(mockedGet).toHaveBeenCalledWith('/api/cards/accounts/3');
    expect(mockedGet).toHaveBeenCalledWith('/api/cards/accounts/3/cards');
  });

  it('발급은 대상(holderUserId)과 서브한도를 본문으로 보낸다', async () => {
    await cardApi.issueCard(3, { holderUserId: 42, subLimit: 500000 });

    expect(mockedPost).toHaveBeenCalledWith('/api/cards/accounts/3/cards', {
      holderUserId: 42,
      subLimit: 500000,
    });
  });

  it('서브한도 변경은 카드 경로에 바꿀 값 하나만 보낸다', async () => {
    await cardApi.changeSubLimit(11, 300000);

    expect(mockedPatch).toHaveBeenCalledWith('/api/cards/cards/11/limit', { subLimit: 300000 });
  });

  it('상태 변경은 목표 상태와 사유를 함께 보낸다 — 사유 없는 정지·해지는 서버가 400 으로 끊는다', async () => {
    await cardApi.changeStatus(11, { status: 'SUSPENDED', reason: '휴직 처리' });

    expect(mockedPatch).toHaveBeenCalledWith('/api/cards/cards/11/status', {
      status: 'SUSPENDED',
      reason: '휴직 처리',
    });
  });

  it('내 카드는 경로에 사용자 식별자가 없다 — /cards/{userId} 는 그대로 IDOR 경로다', async () => {
    mockedGet.mockResolvedValue({ data: [] });
    await cardApi.myCards();

    expect(mockedGet).toHaveBeenCalledWith('/api/cards/cards/me');
  });
});
