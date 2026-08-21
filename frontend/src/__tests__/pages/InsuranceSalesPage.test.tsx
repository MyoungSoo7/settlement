import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import InsuranceSalesPage from '@/pages/system/InsuranceSalesPage';
import {
  proposalApi, applicationApi, policyApi, UnderwritingGateError,
  type ProposalSummary,
} from '@/api/insuranceSales';

vi.mock('@/api/insuranceSales', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/insuranceSales')>();
  return {
    ...actual,
    proposalApi: { create: vi.fn(), get: vi.fn(), convert: vi.fn(), sheet: vi.fn() },
    applicationApi: { submit: vi.fn(), startReview: vi.fn(), approve: vi.fn(), reject: vi.fn() },
    policyApi: { surrender: vi.fn(), cancel: vi.fn(), payouts: vi.fn() },
  };
});
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

const mockedProposal = vi.mocked(proposalApi);
const mockedApp = vi.mocked(applicationApi);
const mockedPolicy = vi.mocked(policyApi);

const proposal = (over: Partial<ProposalSummary> = {}): ProposalSummary => ({
  proposalId: 'PR-1', productCode: 'LIFE-TERM-20', insuredName: '홍길동',
  insuranceAge: 41, coverageAmount: 100_000_000, paymentTermYears: 20,
  appliedRatePerMille: 1.2, annualPremium: 1_200_000, status: 'QUOTED',
  quotedOn: '2026-08-22', validUntil: '2026-09-21', convertedApplicationId: null, ...over,
});

const fillDesign = (channel: 'FC' | 'BANCA' = 'FC') => {
  fireEvent.change(screen.getByLabelText('상품 코드'), { target: { value: 'LIFE-TERM-20' } });
  fireEvent.change(screen.getByLabelText('피보험자명'), { target: { value: '홍길동' } });
  fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1985-03-01' } });
  fireEvent.change(screen.getByLabelText('보장금액'), { target: { value: '100000000' } });
  fireEvent.change(screen.getByLabelText('판매 채널'), { target: { value: channel } });
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('confirm', vi.fn(() => true));
});
afterEach(() => vi.unstubAllGlobals());

/**
 * 이 화면의 값은 <b>식별자를 이어 주는 것</b>이다. 서버에 목록 조회가 없어서, 각 단계의 응답이
 * 주는 다음 단계 열쇠를 화면이 물려주지 않으면 사람이 화면 밖에 적어 두고 다시 입력해야 한다.
 *
 * <p>두 번째 규율: <b>승인 게이트 둘을 갈라서 보여 준다</b>. 완전판매(교부 증빙 없음)와 서류
 * 대사(MATCHED 아님)는 같은 409 지만 갈 곳이 다르다 — 하나는 교부 화면, 하나는 리뷰 큐다.
 */
describe('InsuranceSalesPage — 체인 이어주기', () => {
  it('전환하면 청약 번호가 자동으로 채워진다', async () => {
    mockedProposal.create.mockResolvedValue(proposal());
    mockedProposal.convert.mockResolvedValue({
      proposalId: 'PR-1', applicationId: 'APP-9', annualPremium: 1_200_000,
    });
    render(<InsuranceSalesPage />);

    fillDesign();
    fireEvent.click(screen.getByRole('button', { name: '설계 산출' }));
    await waitFor(() => expect(screen.getByTestId('proposal-id')).toHaveTextContent('PR-1'));

    fireEvent.change(screen.getByLabelText('계약자명'), { target: { value: '김계약' } });
    fireEvent.click(screen.getByRole('button', { name: '청약 전환' }));

    // 사람이 옮겨 적지 않아도 다음 칸이 차 있어야 한다.
    await waitFor(() => expect(screen.getByLabelText('청약 번호')).toHaveValue('APP-9'));
  });

  it('승인하면 증권번호가 계약 구획으로 넘어간다', async () => {
    mockedApp.approve.mockResolvedValue({
      applicationId: 'APP-9', policyId: 'P-1', policyNumber: 'POL-77',
      firstYearCommissionTotal: 300_000, installmentCount: 12,
    });
    render(<InsuranceSalesPage />);

    fireEvent.change(screen.getByLabelText('청약 번호'), { target: { value: 'APP-9' } });
    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(screen.getByTestId('policy-number')).toHaveTextContent('POL-77'));
    expect(screen.getByLabelText('증권번호')).toHaveValue('POL-77');
  });

  it('설계 유효기한을 보여 준다 — 지나면 전환이 409 로 막힌다', async () => {
    mockedProposal.create.mockResolvedValue(proposal());
    render(<InsuranceSalesPage />);
    fillDesign();
    fireEvent.click(screen.getByRole('button', { name: '설계 산출' }));

    await waitFor(() => expect(screen.getByTestId('valid-until')).toHaveTextContent('2026-09-21'));
  });

  it('이미 전환된 설계에는 전환 폼을 그리지 않는다', async () => {
    mockedProposal.create.mockResolvedValue(proposal({ convertedApplicationId: 'APP-3' }));
    render(<InsuranceSalesPage />);
    fillDesign();
    fireEvent.click(screen.getByRole('button', { name: '설계 산출' }));

    await waitFor(() => expect(screen.getByTestId('already-converted')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '청약 전환' })).not.toBeInTheDocument();
  });
});

describe('InsuranceSalesPage — 승인 게이트', () => {
  it('교부 증빙 없음은 교부 화면으로 안내한다', async () => {
    mockedApp.approve.mockRejectedValue(
      new UnderwritingGateError('DISCLOSURE', '상품설명서 교부 증빙이 없습니다'));
    render(<InsuranceSalesPage />);

    fireEvent.change(screen.getByLabelText('청약 번호'), { target: { value: 'APP-9' } });
    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(screen.getByTestId('gate-kind')).toHaveTextContent('완전판매 게이트'));
    expect(screen.getByTestId('gate-alert')).toHaveTextContent('상품설명서 교부에서');
  });

  it('서류 미대사는 리뷰 큐로 안내한다 — 같은 409 지만 갈 곳이 다르다', async () => {
    mockedApp.approve.mockRejectedValue(
      new UnderwritingGateError('DOCUMENT', '첨부 서류가 MATCHED 가 아닙니다'));
    render(<InsuranceSalesPage />);

    fireEvent.change(screen.getByLabelText('청약 번호'), { target: { value: 'APP-9' } });
    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(screen.getByTestId('gate-kind')).toHaveTextContent('서류 대사 게이트'));
    expect(screen.getByTestId('gate-alert')).toHaveTextContent('증빙 리뷰 큐에서');
  });

  it('게이트에 막히면 계약 발행 결과를 그리지 않는다', async () => {
    mockedApp.approve.mockRejectedValue(new UnderwritingGateError('DISCLOSURE', '교부 없음'));
    render(<InsuranceSalesPage />);

    fireEvent.change(screen.getByLabelText('청약 번호'), { target: { value: 'APP-9' } });
    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(screen.getByTestId('gate-alert')).toBeInTheDocument());
    expect(screen.queryByTestId('issued-policy')).not.toBeInTheDocument();
  });
});

describe('InsuranceSalesPage — 방카 채널', () => {
  it('BANCA 를 고르면 제휴은행이 필수가 된다', () => {
    render(<InsuranceSalesPage />);

    expect(screen.queryByLabelText('제휴은행 코드')).not.toBeInTheDocument();

    fillDesign('BANCA');

    expect(screen.getByLabelText('제휴은행 코드')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '설계 산출' })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('제휴은행 코드'), { target: { value: 'BANK-001' } });
    expect(screen.getByRole('button', { name: '설계 산출' })).toBeEnabled();
  });

  it('FC 설계에는 제휴은행을 싣지 않는다', async () => {
    mockedProposal.create.mockResolvedValue(proposal());
    render(<InsuranceSalesPage />);
    fillDesign('FC');

    fireEvent.click(screen.getByRole('button', { name: '설계 산출' }));

    await waitFor(() => expect(mockedProposal.create).toHaveBeenCalled());
    const [input] = mockedProposal.create.mock.calls[0];
    expect(input).not.toHaveProperty('partnerBankCode');
  });
});

describe('InsuranceSalesPage — 계약 종료', () => {
  it('해지와 철회는 확인 문구가 다르다 — 돈의 성격이 다르다', () => {
    // 인자 타입을 적어 둔다 — 인자 없는 함수로 추론되면 mock.calls[0][0] 이 타입 오류다.
    const confirmSpy = vi.fn((_message?: string) => false);
    vi.stubGlobal('confirm', confirmSpy);
    render(<InsuranceSalesPage />);
    fireEvent.change(screen.getByLabelText('증권번호'), { target: { value: 'POL-77' } });

    fireEvent.click(screen.getByRole('button', { name: '해지' }));
    expect(confirmSpy.mock.calls[0][0]).toContain('해지환급금');

    fireEvent.click(screen.getByRole('button', { name: '철회' }));
    expect(confirmSpy.mock.calls[1][0]).toContain('납입 보험료가 반환');

    expect(mockedPolicy.surrender).not.toHaveBeenCalled();
    expect(mockedPolicy.cancel).not.toHaveBeenCalled();
  });

  it('해지 결과의 상태와 지급액을 보여 준다', async () => {
    mockedPolicy.surrender.mockResolvedValue({
      policyNumber: 'POL-77', status: 'SURRENDERED',
      payout: {
        payoutId: 'PO-1', payoutType: 'SURRENDER', amount: 900_000, status: 'PAID',
        requestedOn: '2026-08-22', paidOn: '2026-08-22', paidPremiumTotal: 1_200_000,
        appliedRate: 0.75, elapsedMonths: 12, installmentCount: 1,
      },
    });
    render(<InsuranceSalesPage />);
    fireEvent.change(screen.getByLabelText('증권번호'), { target: { value: 'POL-77' } });

    fireEvent.click(screen.getByRole('button', { name: '해지' }));

    await waitFor(() => expect(screen.getByTestId('policy-status')).toHaveTextContent('SURRENDERED'));
    // 납입 누계를 함께 보여 준다 — 환급금이 납입액보다 적다는 사실이 화면에 있어야 한다.
    expect(screen.getByTestId('termination-result')).toHaveTextContent('1,200,000');
  });

  it('증권번호를 고치면 앞선 결과를 버린다', async () => {
    mockedPolicy.surrender.mockResolvedValue({
      policyNumber: 'POL-77', status: 'SURRENDERED', payout: null,
    });
    render(<InsuranceSalesPage />);
    fireEvent.change(screen.getByLabelText('증권번호'), { target: { value: 'POL-77' } });
    fireEvent.click(screen.getByRole('button', { name: '해지' }));
    await waitFor(() => expect(screen.getByTestId('termination-result')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('증권번호'), { target: { value: 'POL-88' } });

    expect(screen.queryByTestId('termination-result')).not.toBeInTheDocument();
  });
});
