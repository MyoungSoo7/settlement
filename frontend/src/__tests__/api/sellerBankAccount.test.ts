import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError } from 'axios';
import { sellerBankAccountApi } from '@/api/sellerBankAccount';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), put: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

const httpError = (status: number) => {
  const error = new AxiosError('boom');
  error.response = { status, data: null, statusText: '', headers: {}, config: {} as never };
  return error;
};

const view = {
  sellerId: 9, bank: 'KB', account: '****1234', holder: '홍길동', updatedAt: '2026-08-21T00:00:00Z',
};

beforeEach(() => vi.clearAllMocks());

/**
 * 지급 계좌 API 계약.
 *
 * <p>고정하는 것 셋. ① 본인 저장에 셀러 식별자를 <b>보내지 않는다</b> — 서버가 JWT 주체에서만
 * 파생한다(IDOR). ② <b>404 만</b> null 로 접는다 — "등록된 적 없다"와 "권한이 없다"는 다른 사실이라,
 * 403·5xx 까지 접으면 미등록으로 위장한다. ③ 운영자 저장은 URL 의 셀러를 대상으로 한다.
 */
describe('sellerBankAccountApi — 본인', () => {
  it('저장 본문에 셀러 식별자를 싣지 않는다 (IDOR 차단의 형태)', async () => {
    mocked.put.mockResolvedValue({ data: view } as never);

    await sellerBankAccountApi.saveMine({
      bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
    });

    expect(mocked.put).toHaveBeenCalledWith('/api/seller/bank-account', {
      bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
    });
    const [, body] = mocked.put.mock.calls[0];
    expect(body).not.toHaveProperty('sellerId');
  });

  it('등록 전(404)이면 null 이다', async () => {
    mocked.get.mockRejectedValue(httpError(404));
    await expect(sellerBankAccountApi.mine()).resolves.toBeNull();
  });

  it('403 은 null 로 접지 않고 그대로 던진다 — 미등록으로 위장하면 안 된다', async () => {
    mocked.get.mockRejectedValue(httpError(403));
    await expect(sellerBankAccountApi.mine()).rejects.toBeInstanceOf(AxiosError);
  });
});

describe('sellerBankAccountApi — 운영자', () => {
  it('조회·저장 모두 URL 의 셀러를 대상으로 한다', async () => {
    mocked.get.mockResolvedValue({ data: view } as never);
    mocked.put.mockResolvedValue({ data: view } as never);

    await sellerBankAccountApi.of(9);
    expect(mocked.get).toHaveBeenCalledWith('/admin/seller-bank-accounts/9');

    await sellerBankAccountApi.save(9, {
      bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
    });
    expect(mocked.put).toHaveBeenCalledWith('/admin/seller-bank-accounts/9', {
      bankCode: 'KB', accountNumber: '110123456789', accountHolder: '홍길동',
    });
  });

  it('미등록 셀러(404)는 null — 오류가 아니라 답이다', async () => {
    mocked.get.mockRejectedValue(httpError(404));
    await expect(sellerBankAccountApi.of(9)).resolves.toBeNull();
  });
});
