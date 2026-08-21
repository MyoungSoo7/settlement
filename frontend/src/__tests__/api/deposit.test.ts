import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError } from 'axios';
import { depositApi } from '@/api/deposit';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn() } }));

const mocked = vi.mocked(api);

const httpError = (status: number) => {
  const error = new AxiosError('boom');
  error.response = { status, data: null, statusText: '', headers: {}, config: {} as never };
  return error;
};

beforeEach(() => vi.clearAllMocks());

/**
 * 예치금 조회 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 두 가지. ① 본인 조회에 셀러 식별자를 <b>보내지 않는다</b> — 서버가 JWT
 * 주체에서만 파생하므로 화면이 실어 보낼 자리가 없다는 것이 IDOR 방어의 형태다.
 * ② <b>404 만</b> null 로 접는다 — "계좌가 열린 적 없다"와 "잔고가 0이다"는 다른 사실이고,
 * 401·5xx 까지 null 로 접으면 로그인 만료가 "잔고 없음"으로 위장한다.
 */
describe('depositApi.myAccount', () => {
  it('식별자를 싣지 않고 /accounts/me 만 부른다', async () => {
    mocked.get.mockResolvedValue({ data: { id: 1, sellerId: 9 } } as never);

    await depositApi.myAccount();

    expect(mocked.get).toHaveBeenCalledWith('/api/deposits/accounts/me');
    expect(mocked.get.mock.calls[0]).toHaveLength(1);
  });

  it('404 는 null 로 접는다 — 0원 계좌를 지어내지 않는다', async () => {
    mocked.get.mockRejectedValue(httpError(404));

    expect(await depositApi.myAccount()).toBeNull();
  });

  it('401·500 은 그대로 던진다 — null 로 접으면 로그인 만료가 "잔고 없음"으로 위장한다', async () => {
    mocked.get.mockRejectedValue(httpError(401));
    await expect(depositApi.myAccount()).rejects.toBeInstanceOf(AxiosError);

    mocked.get.mockRejectedValue(httpError(500));
    await expect(depositApi.myAccount()).rejects.toBeInstanceOf(AxiosError);
  });

  it('axios 가 아닌 오류도 그대로 던진다', async () => {
    mocked.get.mockRejectedValue(new TypeError('network down'));

    await expect(depositApi.myAccount()).rejects.toBeInstanceOf(TypeError);
  });
});

describe('depositApi.accountOf', () => {
  it('셀러 식별자를 경로에 싣는다', async () => {
    mocked.get.mockResolvedValue({ data: { id: 1, sellerId: 42 } } as never);

    await depositApi.accountOf(42);

    expect(mocked.get).toHaveBeenCalledWith('/api/deposits/accounts/42');
  });

  it('없으면 null, 그 밖의 오류는 던진다', async () => {
    mocked.get.mockRejectedValue(httpError(404));
    expect(await depositApi.accountOf(42)).toBeNull();

    mocked.get.mockRejectedValue(httpError(403));
    await expect(depositApi.accountOf(42)).rejects.toBeInstanceOf(AxiosError);
  });
});
