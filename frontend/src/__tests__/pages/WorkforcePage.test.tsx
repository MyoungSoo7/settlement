import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type {
  WorkforceComparison,
  WorkforceHistory,
  WorkforcePage as WorkforcePageData,
} from '@/api/company';
import WorkforcePage from '@/pages/WorkforcePage';

/**
 * 이슈 #184 — 백엔드 금액 계약은 BigDecimal 을 소수 문자열로 내려주는데(AGENTS.md 돈 규칙),
 * 화면이 이를 `Number()` 로 바꿔 포맷하면 안전 정수 범위(2^53-1) 밖에서 마지막 자리가
 * 조용히 바뀌어 *틀린 금액*이 표시된다. 아래 단언은 헬퍼가 아니라 실제 렌더 결과를 본다.
 *
 * 표본값 `9007199254740993` 은 `Number()` 를 거치면 `9007199254740992` 가 되는 값이라,
 * 회귀 시 여기서 먼저 깨진다.
 */
const EXACT_SALARY = '9007199254740993';
const EXACT_SALARY_RENDERED = '9,007,199,254,740,993원';

const { workforce, workforceDetail, workforceHistory } = vi.hoisted(() => ({
  workforce: vi.fn(),
  workforceDetail: vi.fn(),
  workforceHistory: vi.fn(),
}));

vi.mock('@/api/company', () => ({
  companyApi: { workforce, workforceDetail, workforceHistory },
}));

const listPage: WorkforcePageData = {
  content: [
    {
      workplaceName: '르무엘테스트',
      bizRegNoPrefix: '123456',
      industryName: '소프트웨어 개발',
      address: '서울특별시 강남구',
      headcount: 42,
      estimatedAnnualSalary: null,
      snapshotMonth: '2026-06',
      note: '',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

const detail: WorkforceComparison = {
  workplaceName: '르무엘테스트',
  bizRegNoPrefix: '123456',
  snapshotMonth: '2026-06',
  industryCode: '620',
  industryName: '소프트웨어 개발',
  address: '서울특별시 강남구',
  sido: '서울특별시',
  sigungu: '강남구',
  headcount: 42,
  estimatedAnnualSalary: EXACT_SALARY,
  salaryCapReached: false,
  salaryCapMonthlyAmount: null,
  industryComparison: {
    comparisonLevel: 'EXACT',
    groupKey: '620',
    sampleSize: 120,
    unavailableReason: null,
    headcount: { median: 30, difference: 12, differenceRate: 40, percentile: 78 },
    estimatedAnnualSalary: {
      median: '43750000.00',
      difference: '-0.0000001',
      differenceRate: null,
      percentile: 61,
    },
  },
  regionComparison: {
    comparisonLevel: null,
    groupKey: null,
    sampleSize: 0,
    unavailableReason: 'REGION_UNPARSEABLE',
    headcount: null,
    estimatedAnnualSalary: null,
  },
  note: '',
};

const history: WorkforceHistory = {
  workplaceName: '르무엘테스트',
  bizRegNoPrefix: '123456',
  series: [
    {
      snapshotMonth: '2026-05',
      headcount: 40,
      estimatedAnnualSalary: '43750000.00',
      salaryCapReached: false,
      headcountChange: null,
      headcountChangeRate: null,
      salaryChange: null,
      salaryChangeRate: null,
    },
    {
      snapshotMonth: '2026-06',
      headcount: 42,
      estimatedAnnualSalary: EXACT_SALARY,
      salaryCapReached: false,
      headcountChange: 2,
      headcountChangeRate: 5,
      salaryChange: '1234.5678',
      salaryChangeRate: 0.01,
    },
  ],
  note: '',
};

const openComparison = async () => {
  render(
    <MemoryRouter>
      <WorkforcePage />
    </MemoryRouter>,
  );
  const button = await screen.findByRole('button', { name: '비교 보기' });
  await userEvent.click(button);
  await waitFor(() => expect(workforceDetail).toHaveBeenCalled());
};

describe('WorkforcePage 금액 표시', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    workforce.mockResolvedValue(listPage);
    workforceDetail.mockResolvedValue(detail);
    workforceHistory.mockResolvedValue(history);
  });

  it('안전 정수 범위를 넘는 추정연봉을 계약값 그대로 보여준다', async () => {
    await openComparison();
    // 상세 카드 + 추이 표 두 곳에 같은 금액이 나온다
    const rendered = await screen.findAllByText(EXACT_SALARY_RENDERED);
    expect(rendered.length).toBeGreaterThan(0);
    // Number() 를 거쳤을 때 나오는 값이 화면에 있으면 안 된다
    expect(screen.queryByText('9,007,199,254,740,992원')).not.toBeInTheDocument();
  });

  it('중앙값 대비 차이의 유효 소수 자리를 반올림 없이 보여준다', async () => {
    await openComparison();
    expect(await screen.findByText('-0.0000001원')).toBeInTheDocument();
  });

  it('전월 대비 증감의 소수 자리를 잘라내지 않고 부호를 붙인다', async () => {
    await openComparison();
    expect(await screen.findByText(/\+1,234\.5678원/)).toBeInTheDocument();
  });

  it('소수부가 0뿐인 금액은 기존처럼 정수로 보여준다', async () => {
    await openComparison();
    const rendered = await screen.findAllByText('43,750,000원');
    expect(rendered.length).toBeGreaterThan(0);
  });
});
