import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError } from 'axios';
import { securedLoanApi, DuplicateEnforcementError } from '@/api/securedLoan';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

const httpError = (status: number) => {
  const error = new AxiosError('boom');
  error.response = { status, data: null, statusText: '', headers: {}, config: {} as never };
  return error;
};

beforeEach(() => vi.clearAllMocks());

/**
 * 담보 API 계약.
 *
 * <p>고정하는 것 셋. ① 처분·대위변제는 <b>항상 Idempotency-Key 를 붙인다</b> — 서버는 키가 없으면
 * 중복 방어를 아예 적용하지 않아(하위호환) 더블클릭이 두 번 집행된다.
 * ② <b>409 는 실패가 아니다</b> — 같은 키의 재제출을 서버가 선점으로 막은 것이라, 원 요청은
 * 성공했을 수 있다. 실패로 뭉개면 운영자가 다시 집행하려 든다.
 * ③ 대위변제는 매각대금을 싣지 않는다 — 보증기관이 보증비율만큼 갚는 다른 경로다.
 */
describe('securedLoanApi — 조회', () => {
  it('없는 대출은 null (오류가 아니라 답이다)', async () => {
    mocked.get.mockRejectedValue(httpError(404));
    await expect(securedLoanApi.detail(7)).resolves.toBeNull();
  });

  it('403 은 삼키지 않는다 — 권한 없음이 "없는 대출"로 위장하면 안 된다', async () => {
    mocked.get.mockRejectedValue(httpError(403));
    await expect(securedLoanApi.detail(7)).rejects.toBeInstanceOf(AxiosError);
  });
});

describe('securedLoanApi — 재평가', () => {
  it('평가액과 출처를 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: { outcome: 'MARGIN_CALL' } } as never);

    await securedLoanApi.revalue(7, 5_000_000, 'MANUAL');

    expect(mocked.post).toHaveBeenCalledWith('/loans/secured/7/collateral/revalue',
      { revaluedValue: 5_000_000, source: 'MANUAL' });
  });

  it('재평가에는 멱등 키를 붙이지 않는다 — 같은 값 재평가는 같은 판정이라 무해하다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await securedLoanApi.revalue(7, 5_000_000, 'MANUAL');

    // 세 번째 인자(config)가 없다 = 헤더를 싣지 않았다.
    expect(mocked.post.mock.calls[0]).toHaveLength(2);
  });
});

describe('securedLoanApi — 실행', () => {
  it('처분은 매각대금과 멱등 키를 함께 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: { recovered: 1000 } } as never);

    await securedLoanApi.dispose(7, 3_000_000, 'key-1');

    expect(mocked.post).toHaveBeenCalledWith('/loans/secured/7/collateral/dispose',
      { proceeds: 3_000_000 }, { headers: { 'Idempotency-Key': 'key-1' } });
  });

  it('대위변제는 본문이 없고 멱등 키만 붙는다', async () => {
    mocked.post.mockResolvedValue({ data: { recovered: 850 } } as never);

    await securedLoanApi.subrogate(7, 'key-2');

    expect(mocked.post).toHaveBeenCalledWith('/loans/secured/7/collateral/subrogate',
      null, { headers: { 'Idempotency-Key': 'key-2' } });
  });

  it('409 는 DuplicateEnforcementError 로 구분된다 — 재집행을 유도하면 안 된다', async () => {
    mocked.post.mockRejectedValue(httpError(409));

    await expect(securedLoanApi.dispose(7, 100, 'key-1'))
      .rejects.toBeInstanceOf(DuplicateEnforcementError);
    await expect(securedLoanApi.subrogate(7, 'key-1'))
      .rejects.toBeInstanceOf(DuplicateEnforcementError);
  });

  it('409 가 아닌 오류는 그대로 던진다', async () => {
    mocked.post.mockRejectedValue(httpError(500));
    await expect(securedLoanApi.dispose(7, 100, 'key-1')).rejects.toBeInstanceOf(AxiosError);
  });
});
