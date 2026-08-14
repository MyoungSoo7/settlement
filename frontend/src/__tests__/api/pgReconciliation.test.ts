import { describe, it, expect, vi, beforeEach } from 'vitest';
import { pgReconciliationApi } from '@/api/pgReconciliation';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

const run = { id: 11, provider: 'TOSS', targetDate: '2026-08-13', status: 'OPEN' };
const discrepancy = { id: 3, type: 'AMOUNT', status: 'PENDING' };

beforeEach(() => vi.clearAllMocks());

/**
 * PG 대사 API 클라이언트 계약 — 핵심은 <b>봉투(envelope) 벗기기</b>다.
 *
 * <p>서버는 {@code {run: ...}} · {@code {discrepancy: ...}} 로 감싸 내려주고 화면은 알맹이만 쓴다.
 * 이 벗기기가 어긋나면 화면은 `undefined` 를 받아 빈 표를 그리는데, 네트워크는 200 이라
 * 장애로 보이지 않는다 — 그래서 모양을 테스트로 고정한다.
 */
describe('pgReconciliationApi — 조회', () => {
  it('실행 목록은 봉투를 벗겨 run 배열로 준다 (limit 은 쿼리 파라미터)', async () => {
    mocked.get.mockResolvedValue({ data: [{ run }, { run: { ...run, id: 12 } }] } as never);

    const runs = await pgReconciliationApi.runs(5);

    expect(mocked.get).toHaveBeenCalledWith('/admin/pg-reconciliation/runs', { params: { limit: 5 } });
    expect(runs.map((r) => r.id)).toEqual([11, 12]);
  });

  it('limit 을 생략하면 20 이 기본이다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await pgReconciliationApi.runs();

    expect(mocked.get).toHaveBeenCalledWith('/admin/pg-reconciliation/runs', { params: { limit: 20 } });
  });

  it('실행 상세는 run 과 차이 목록을 각각 벗겨 준다', async () => {
    mocked.get.mockResolvedValue({ data: { run, discrepancies: [{ discrepancy }] } } as never);

    const detail = await pgReconciliationApi.runDetail(11);

    expect(mocked.get).toHaveBeenCalledWith('/admin/pg-reconciliation/runs/11');
    expect(detail.run.id).toBe(11);
    expect(detail.discrepancies).toEqual([discrepancy]);
  });

  it('회수 미리보기는 봉투 없이 그대로 준다 (읽기 전용 경로)', async () => {
    mocked.get.mockResolvedValue({ data: { affectedSettlements: 2 } } as never);

    await expect(pgReconciliationApi.clawbackPreview(11)).resolves.toEqual({ affectedSettlements: 2 });
    expect(mocked.get).toHaveBeenCalledWith('/admin/pg-reconciliation/runs/11/clawback-preview');
  });
});

describe('pgReconciliationApi — 실행', () => {
  it('업로드는 multipart 로 3개 필드를 실어 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: { run } } as never);
    const file = new File(['a,b,c'], 'toss-20260813.csv', { type: 'text/csv' });

    const created = await pgReconciliationApi.upload('TOSS', '2026-08-13', file);

    const [url, form, config] = mocked.post.mock.calls[0];
    expect(url).toBe('/admin/pg-reconciliation/files');
    expect(form).toBeInstanceOf(FormData);
    expect((form as FormData).get('provider')).toBe('TOSS');
    expect((form as FormData).get('targetDate')).toBe('2026-08-13');
    expect((form as FormData).get('file')).toBe(file);
    expect(config).toEqual({ headers: { 'Content-Type': 'multipart/form-data' } });
    expect(created.id).toBe(11);
  });

  it('승인·거절은 사유를 실어 보내고 차이 알맹이를 돌려준다', async () => {
    mocked.post.mockResolvedValue({ data: { discrepancy } } as never);

    await expect(pgReconciliationApi.approve(3, '정산 반영 확인')).resolves.toEqual(discrepancy);
    expect(mocked.post).toHaveBeenCalledWith(
      '/admin/pg-reconciliation/discrepancies/3/approve', { note: '정산 반영 확인' });

    await expect(pgReconciliationApi.reject(3, 'PG 오기입')).resolves.toEqual(discrepancy);
    expect(mocked.post).toHaveBeenCalledWith(
      '/admin/pg-reconciliation/discrepancies/3/reject', { note: 'PG 오기입' });
  });

  it('마감은 사유가 선택이다 — 생략하면 undefined 로 나간다', async () => {
    mocked.post.mockResolvedValue({ data: { run: { ...run, status: 'CLOSED' } } } as never);

    const closed = await pgReconciliationApi.close(11);

    expect(mocked.post).toHaveBeenCalledWith('/admin/pg-reconciliation/runs/11/close', { note: undefined });
    expect(closed.status).toBe('CLOSED');
  });
});
