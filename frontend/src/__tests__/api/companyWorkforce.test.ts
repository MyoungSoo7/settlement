import { describe, it, expect, vi, beforeEach } from 'vitest';
import { companyApi, WorkforceComparison, WorkforceHistory, WorkforcePage } from '@/api/company';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    defaults: { baseURL: '' },
  },
}));

const workforcePage: WorkforcePage = {
  content: [
    {
      workplaceName: '주식회사에고이즘',
      bizRegNoPrefix: '866759',
      industryName: '전자상거래 소매업',
      address: '서울특별시 성동구 연무장19길',
      headcount: 50,
      estimatedAnnualSalary: 43750000,
      snapshotMonth: '2026-06',
      note: '국민연금 기준소득월액 상한 적용 추정치입니다',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('companyApi.workforce', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('사업장명 검색어와 페이지를 쿼리로 전달한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: workforcePage });

    const result = await companyApi.workforce('에고이즘', 0);

    expect(api.get).toHaveBeenCalledWith(
      `/api/company/workforce?page=0&size=20&name=${encodeURIComponent('에고이즘')}`
    );
    // 상세 진입에 필요한 복합키 3요소가 목록 행에 모두 있어야 한다
    expect(result.content[0].bizRegNoPrefix).toBe('866759');
    expect(result.content[0].snapshotMonth).toBe('2026-06');
    expect(result.content[0].workplaceName).toBe('주식회사에고이즘');
  });

  it('빈 검색어는 name 파라미터를 생략한다 (전체 조회)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { ...workforcePage, content: [] } });

    await companyApi.workforce('   ', 2);

    expect(api.get).toHaveBeenCalledWith('/api/company/workforce?page=2&size=20');
  });
});

describe('companyApi.workforceDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('복합키 3요소를 query parameter 로 보내고 비교 응답을 반환한다', async () => {
    const detail: WorkforceComparison = {
      workplaceName: '주식회사에고이즘',
      bizRegNoPrefix: '866759',
      snapshotMonth: '2026-06',
      industryCode: '525101',
      industryName: '전자상거래 소매업',
      address: '서울특별시 성동구 연무장19길',
      sido: '서울특별시',
      sigungu: '성동구',
      headcount: 50,
      estimatedAnnualSalary: '43750000', // 금액은 소수 문자열 계약
      salaryCapReached: false,
      salaryCapMonthlyAmount: '6370000',
      industryComparison: {
        comparisonLevel: 'EXACT',
        groupKey: '525101',
        sampleSize: 12,
        unavailableReason: null,
        headcount: { median: 12.5, difference: 37.5, differenceRate: 300.0, percentile: 91.2 },
        estimatedAnnualSalary: { median: '35000000', difference: '8750000', differenceRate: 25.0, percentile: 82.5 },
      },
      regionComparison: {
        comparisonLevel: 'BROADENED',
        groupKey: '서울특별시',
        sampleSize: 4,
        unavailableReason: 'SAMPLE_TOO_SMALL',
        headcount: null,
        estimatedAnnualSalary: null,
      },
      note: '국민연금 기준소득월액 상한 적용 추정치입니다',
    };
    vi.mocked(api.get).mockResolvedValueOnce({ data: detail });

    const result = await companyApi.workforceDetail('주식회사에고이즘', '866759', '2026-06');

    expect(api.get).toHaveBeenCalledWith(
      `/api/company/workforce/detail?name=${encodeURIComponent('주식회사에고이즘')}&bizRegNoPrefix=866759&snapshotMonth=2026-06`
    );
    expect(result.estimatedAnnualSalary).toBe('43750000');
    expect(result.industryComparison.estimatedAnnualSalary?.median).toBe('35000000');
    expect(result.regionComparison.unavailableReason).toBe('SAMPLE_TOO_SMALL');
  });

  it('따옴표·느낌표가 든 사업장명도 표준 URL 인코딩으로 전달된다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: {} as WorkforceComparison });

    const quoted = '(유)케이비에프에스"전주밥상 다잡수소!"';
    await companyApi.workforceDetail(quoted, '418851', '2026-06');

    const calledUrl = vi.mocked(api.get).mock.calls[0][0] as string;
    // 원문 특수문자가 그대로 노출되지 않고 인코딩되어 있어야 한다
    expect(calledUrl).not.toContain('"');
    const params = new URLSearchParams(calledUrl.split('?')[1]);
    expect(params.get('name')).toBe(quoted); // 왕복(인코딩→디코딩) 무손실
    expect(params.get('bizRegNoPrefix')).toBe('418851');
  });
});

describe('companyApi.workforceHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('2요소 키(월 없음)를 query parameter 로 보내고 시계열을 반환한다', async () => {
    const history: WorkforceHistory = {
      workplaceName: '주식회사에고이즘',
      bizRegNoPrefix: '866759',
      series: [
        {
          snapshotMonth: '2026-05', headcount: 50, estimatedAnnualSalary: '43750000',
          salaryCapReached: false, headcountChange: null, headcountChangeRate: null,
          salaryChange: null, salaryChangeRate: null,
        },
        {
          snapshotMonth: '2026-06', headcount: 60, estimatedAnnualSalary: '40000000',
          salaryCapReached: false, headcountChange: 10, headcountChangeRate: 20.0,
          salaryChange: '-3750000', salaryChangeRate: -8.57,
        },
      ],
      note: '증감은 연속된 인접 월 사이에서만 계산됩니다.',
    };
    vi.mocked(api.get).mockResolvedValueOnce({ data: history });

    const result = await companyApi.workforceHistory('주식회사에고이즘', '866759');

    expect(api.get).toHaveBeenCalledWith(
      `/api/company/workforce/history?name=${encodeURIComponent('주식회사에고이즘')}&bizRegNoPrefix=866759`
    );
    // 금액은 문자열 계약 그대로, 첫 월 증감은 null
    expect(result.series[0].salaryChange).toBeNull();
    expect(result.series[1].salaryChange).toBe('-3750000');
    expect(result.series[1].headcountChange).toBe(10);
  });
});
