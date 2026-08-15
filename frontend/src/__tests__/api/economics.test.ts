import { describe, it, expect, vi, beforeEach } from 'vitest';
import { economicsApi, fetchIndicators, fetchIndicatorSeries } from '@/api/economics';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const indicator = {
  code: 'BASE_RATE',
  name: '한국은행 기준금리',
  unit: '%',
  cycle: 'D',
  latest: { observedDate: '2026-08-01', value: 3.0 },
  change: { amount: -0.25, ratePercent: -7.69 },
};

describe('economicsApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('지표 카탈로그를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [indicator] });

    const result = await economicsApi.indicators();

    expect(api.get).toHaveBeenCalledWith('/api/economics/indicators');
    expect(result[0].code).toBe('BASE_RATE');
  });

  it('시계열은 기간 미지정이면 쿼리 없이 호출한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { code: 'BASE_RATE', name: '기준금리', unit: '%', points: [] },
    });

    await economicsApi.series('BASE_RATE');

    expect(api.get).toHaveBeenCalledWith('/api/economics/indicators/BASE_RATE/series');
  });

  it('from 만 주면 from 만 쿼리에 실린다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { code: 'CPI', name: '소비자물가', unit: '2020=100', points: [] },
    });

    await economicsApi.series('CPI', '2026-01-01');

    expect(api.get).toHaveBeenCalledWith('/api/economics/indicators/CPI/series?from=2026-01-01');
  });

  it('to 만 주면 to 만 쿼리에 실린다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { code: 'CPI', name: '소비자물가', unit: '2020=100', points: [] },
    });

    await economicsApi.series('CPI', undefined, '2026-08-01');

    expect(api.get).toHaveBeenCalledWith('/api/economics/indicators/CPI/series?to=2026-08-01');
  });

  it('from·to 를 모두 주면 둘 다 실린다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        code: 'USD_KRW',
        name: '원/달러',
        unit: 'KRW',
        points: [{ observedDate: '2026-08-01', value: 1380, source: 'ECOS' }],
      },
    });

    const result = await economicsApi.series('USD_KRW', '2026-01-01', '2026-08-01');

    expect(api.get).toHaveBeenCalledWith(
      '/api/economics/indicators/USD_KRW/series?from=2026-01-01&to=2026-08-01',
    );
    expect(result.points[0].source).toBe('ECOS');
  });

  it('이름 있는 별칭 export 는 같은 함수를 가리킨다', () => {
    expect(fetchIndicators).toBe(economicsApi.indicators);
    expect(fetchIndicatorSeries).toBe(economicsApi.series);
  });
});
