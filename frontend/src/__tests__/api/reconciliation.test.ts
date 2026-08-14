import { describe, it, expect, vi, beforeEach } from 'vitest';
import { reconciliationApi, scanReconciliation, type ReconciliationReport } from '@/api/reconciliation';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const report = (date: string, matched = true): ReconciliationReport => ({
  targetDate: date,
  capturedPayments: '1000000',
  settlementGross: matched ? '1000000' : '990000',
  refundedAgainstCaptures: '0',
  settlementRefunded: '0',
  captureDiscrepancy: matched ? '0' : '10000',
  refundDiscrepancy: '0',
  discrepancy: matched ? '0' : '10000',
  capturedCount: 10,
  settlementCount: matched ? 10 : 9,
  countDiscrepancy: matched ? 0 : 1,
  matched,
});

describe('reconciliationApi.run', () => {
  beforeEach(() => vi.resetAllMocks());

  it('하루치 대사를 date 파라미터로 실행한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: report('2026-08-01') });

    const result = await reconciliationApi.run('2026-08-01');

    expect(api.get).toHaveBeenCalledWith('/admin/reconciliation', { params: { date: '2026-08-01' } });
    expect(result.matched).toBe(true);
  });

  it('불일치 리포트는 matched=false 와 차액을 그대로 전달한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: report('2026-08-02', false) });

    const result = await reconciliationApi.run('2026-08-02');

    expect(result.matched).toBe(false);
    expect(result.captureDiscrepancy).toBe('10000');
    expect(result.countDiscrepancy).toBe(1);
  });
});

describe('scanReconciliation (기간 스캔)', () => {
  beforeEach(() => vi.resetAllMocks());

  it('날짜마다 셀을 만들고 순서를 보존한다', async () => {
    const dates = ['2026-08-01', '2026-08-02', '2026-08-03'];
    vi.mocked(api.get).mockImplementation((_url, config) =>
      Promise.resolve({ data: report((config as { params: { date: string } }).params.date) }) as never,
    );

    const cells = await scanReconciliation(dates);

    expect(cells.map((c) => c.date)).toEqual(dates);
    expect(cells.every((c) => c.report !== null && c.error === null)).toBe(true);
  });

  it('한 날짜가 실패해도 나머지 날짜 판정은 남긴다', async () => {
    vi.mocked(api.get).mockImplementation((_url, config) => {
      const date = (config as { params: { date: string } }).params.date;
      return date === '2026-08-02'
        ? (Promise.reject(new Error('조회 타임아웃')) as never)
        : (Promise.resolve({ data: report(date) }) as never);
    });

    const cells = await scanReconciliation(['2026-08-01', '2026-08-02', '2026-08-03']);

    expect(cells[0].report).not.toBeNull();
    expect(cells[1].report).toBeNull();
    expect(cells[1].error).toBe('조회 타임아웃');
    expect(cells[2].report).not.toBeNull();
  });

  it('Error 가 아닌 값으로 실패하면 기본 문구를 남긴다', async () => {
    vi.mocked(api.get).mockRejectedValue('boom');

    const cells = await scanReconciliation(['2026-08-01']);

    expect(cells[0].error).toBe('조회 실패');
  });

  it('배치 크기만큼 나눠 호출한다 — 무거운 대사 쿼리를 한꺼번에 던지지 않는다', async () => {
    const dates = Array.from({ length: 7 }, (_, i) => `2026-08-0${i + 1}`);
    const inFlight: number[] = [];
    let current = 0;
    vi.mocked(api.get).mockImplementation((_url, config) => {
      current += 1;
      inFlight.push(current);
      return new Promise((resolve) => {
        setTimeout(() => {
          current -= 1;
          resolve({ data: report((config as { params: { date: string } }).params.date) } as never);
        }, 0);
      });
    });

    const cells = await scanReconciliation(dates, 3);

    expect(cells).toHaveLength(7);
    expect(Math.max(...inFlight)).toBeLessThanOrEqual(3);
  });

  it('빈 날짜 목록이면 호출 없이 빈 배열을 돌려준다', async () => {
    const cells = await scanReconciliation([]);

    expect(cells).toEqual([]);
    expect(api.get).not.toHaveBeenCalled();
  });
});
