import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LoanPage from '@/pages/LoanPage';
import { loanApi } from '@/api/loan';
import { financialApi } from '@/api/financial';
import { authApi } from '@/api/auth';

vi.mock('@/api/loan', () => ({
  loanApi: {
    request: vi.fn(),
    bySeller: vi.fn(),
    disburse: vi.fn(),
    markOverdue: vi.fn(),
    writeOff: vi.fn(),
    corporateCredit: vi.fn(),
    corporateByStock: vi.fn(),
    requestCorporate: vi.fn(),
    disburseCorporate: vi.fn(),
    repayCorporate: vi.fn(),
  },
}));

vi.mock('@/api/financial', () => ({
  financialApi: { companies: vi.fn(), statements: vi.fn() },
}));

vi.mock('@/api/auth', () => ({
  authApi: { getCurrentUser: vi.fn() },
}));

const mockedLoan = vi.mocked(loanApi);
const mockedFinancial = vi.mocked(financialApi);
const mockedAuth = vi.mocked(authApi);

const sellerLoan = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    sellerId: 1,
    principal: 1_000_000,
    fee: 10_000,
    outstanding: 1_010_000,
    dueAt: '2026-08-21T00:00:00',
    status: 'REQUESTED',
    ...over,
  }) as never;

const corporateLoan = (over: Record<string, unknown> = {}) =>
  ({
    id: 11,
    stockCode: '005930',
    principal: 10_000_000,
    fee: 50_000,
    outstanding: 10_050_000,
    termDays: 30,
    creditGrade: 'A',
    creditScore: 900,
    status: 'REQUESTED',
    ...over,
  }) as never;

const credit = (over: Record<string, unknown> = {}) =>
  ({
    stockCode: '005930',
    corpName: '삼성전자',
    market: 'KOSPI',
    fiscalYear: 2025,
    creditGrade: 'A',
    creditScore: 900,
    limit: 50_000_000,
    debtRatio: 25.5,
    operatingMargin: 12.3,
    roa: null,
    reputationGrade: null,
    ...over,
  }) as never;

const company = { stockCode: '005930', corpCode: '00126380', name: '삼성전자', market: 'KOSPI' };

const companyPage = (content = [company]) =>
  ({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1 }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedAuth.getCurrentUser.mockReturnValue({ id: 1, email: 'u@e.com', role: 'USER' } as never);
  mockedLoan.bySeller.mockResolvedValue([] as never);
  mockedLoan.corporateByStock.mockResolvedValue([] as never);
});

const gotoCorporateTab = async () => {
  render(<LoanPage />);
  await userEvent.click(screen.getByRole('button', { name: '기업대출' }));
};

describe('LoanPage — 탭', () => {
  it('기본은 셀러 선정산 탭이며 설명 문구가 따라 바뀐다', async () => {
    render(<LoanPage />);

    expect(screen.getByText(/미확정 정산금을 담보로 선지급/)).toBeInTheDocument();
    expect(screen.getByText('선정산 대출 신청')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '기업대출' }));

    expect(screen.getByText(/코스피 상장사 신용평가 기반/)).toBeInTheDocument();
    expect(screen.getByText('종목 검색')).toBeInTheDocument();
  });

  it('다시 셀러 탭으로 돌아올 수 있다', async () => {
    render(<LoanPage />);
    await userEvent.click(screen.getByRole('button', { name: '기업대출' }));

    await userEvent.click(screen.getByRole('button', { name: '셀러 선정산' }));

    expect(screen.getByText('선정산 대출 신청')).toBeInTheDocument();
  });
});

describe('LoanPage — 셀러 선정산', () => {
  it('신청하면 수수료·미상환을 안내하고 그 셀러의 목록을 다시 읽는다', async () => {
    mockedLoan.request.mockResolvedValue(sellerLoan({ id: 7 }));
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 7 })] as never);
    render(<LoanPage />);

    fireEvent.click(screen.getByRole('button', { name: '대출 신청' }));

    await waitFor(() =>
      expect(mockedLoan.request).toHaveBeenCalledWith({
        sellerId: 1,
        principal: 1000000,
        financingDays: 7,
      }),
    );
    expect(await screen.findByText(/대출 신청 완료 — #7/)).toBeInTheDocument();
    expect(mockedLoan.bySeller).toHaveBeenCalledWith(1);
  });

  it('입력값을 바꾸면 그 값으로 신청한다', async () => {
    mockedLoan.request.mockResolvedValue(sellerLoan({ id: 8 }));
    const { container } = render(<LoanPage />);

    const inputs = container.querySelectorAll<HTMLInputElement>('input[type="number"]');
    fireEvent.change(inputs[0], { target: { value: '5' } });
    fireEvent.change(inputs[1], { target: { value: '2000000' } });
    fireEvent.change(inputs[2], { target: { value: '14' } });
    fireEvent.click(screen.getByRole('button', { name: '대출 신청' }));

    await waitFor(() =>
      expect(mockedLoan.request).toHaveBeenCalledWith({
        sellerId: 5,
        principal: 2000000,
        financingDays: 14,
      }),
    );
  });

  it('신청이 실패하면 사유를 화면에 남긴다', async () => {
    mockedLoan.request.mockRejectedValue({ response: { data: { message: '한도 초과' } } });
    render(<LoanPage />);

    fireEvent.click(screen.getByRole('button', { name: '대출 신청' }));

    expect(await screen.findByText('한도 초과')).toBeInTheDocument();
  });

  it('조회 결과가 없으면 빈 상태를 문장으로 알린다', async () => {
    render(<LoanPage />);

    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    expect(await screen.findByText(/조회된 대출이 없습니다/)).toBeInTheDocument();
  });

  it('조회 실패도 화면에 남긴다', async () => {
    mockedLoan.bySeller.mockRejectedValue(new Error('down'));
    render(<LoanPage />);

    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    expect(await screen.findByText('대출 목록 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('REQUESTED/APPROVED 는 실행 버튼이 뜨고, 실행하면 목록을 다시 읽는다', async () => {
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 3, status: 'APPROVED' })] as never);
    mockedLoan.disburse.mockResolvedValue(undefined as never);
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await userEvent.click(await screen.findByRole('button', { name: '실행(선지급)' }));

    await waitFor(() => expect(mockedLoan.disburse).toHaveBeenCalledWith(3));
    expect(await screen.findByText('#3 대출 실행(선지급) 완료')).toBeInTheDocument();
    expect(mockedLoan.bySeller).toHaveBeenCalledTimes(2);
  });

  it('실행 실패는 사유를 남긴다', async () => {
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 3 })] as never);
    mockedLoan.disburse.mockRejectedValue(new Error('boom'));
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await userEvent.click(await screen.findByRole('button', { name: '실행(선지급)' }));

    expect(await screen.findByText('대출 실행에 실패했습니다.')).toBeInTheDocument();
  });

  it('일반 사용자에게는 연체·상각 버튼이 보이지 않는다 (회수 담당자 전용)', async () => {
    mockedLoan.bySeller.mockResolvedValue([
      sellerLoan({ id: 4, status: 'DISBURSED' }),
      sellerLoan({ id: 5, status: 'OVERDUE' }),
    ] as never);
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await screen.findByText('#4');
    expect(screen.queryByRole('button', { name: '연체' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '상각' })).not.toBeInTheDocument();
  });

  it('ADMIN 은 DISBURSED 를 연체 처리할 수 있다', async () => {
    mockedAuth.getCurrentUser.mockReturnValue({ id: 9, email: 'a@e.com', role: 'ADMIN' } as never);
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 4, status: 'DISBURSED' })] as never);
    mockedLoan.markOverdue.mockResolvedValue(undefined as never);
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await userEvent.click(await screen.findByRole('button', { name: '연체' }));

    await waitFor(() => expect(mockedLoan.markOverdue).toHaveBeenCalledWith(4));
    expect(await screen.findByText('#4 연체 처리 완료')).toBeInTheDocument();
  });

  it('ADMIN 은 OVERDUE 를 상각할 수 있다', async () => {
    mockedAuth.getCurrentUser.mockReturnValue({ id: 9, email: 'a@e.com', role: 'ADMIN' } as never);
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 5, status: 'OVERDUE' })] as never);
    mockedLoan.writeOff.mockResolvedValue(undefined as never);
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await userEvent.click(await screen.findByRole('button', { name: '상각' }));

    await waitFor(() => expect(mockedLoan.writeOff).toHaveBeenCalledWith(5));
    expect(await screen.findByText('#5 상각(대손) 처리 완료')).toBeInTheDocument();
  });

  it('상각 실패도 사유를 남긴다', async () => {
    mockedAuth.getCurrentUser.mockReturnValue({ id: 9, email: 'a@e.com', role: 'ADMIN' } as never);
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 5, status: 'OVERDUE' })] as never);
    mockedLoan.writeOff.mockRejectedValue(new Error('boom'));
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await userEvent.click(await screen.findByRole('button', { name: '상각' }));

    expect(await screen.findByText('상각 처리에 실패했습니다.')).toBeInTheDocument();
  });

  it('종결 상태(REPAID)는 조작 버튼 대신 -를 보여 준다', async () => {
    mockedLoan.bySeller.mockResolvedValue([sellerLoan({ id: 6, status: 'REPAID' })] as never);
    render(<LoanPage />);
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    const row = (await screen.findByText('#6')).closest('tr') as HTMLElement;
    expect(within(row).getByText('-')).toBeInTheDocument();
    expect(within(row).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('LoanPage — 기업대출', () => {
  it('종목을 검색해 결과를 고를 수 있다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    await gotoCorporateTab();

    await userEvent.type(screen.getByPlaceholderText('기업명 또는 종목코드'), '삼성');
    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(mockedFinancial.companies).toHaveBeenCalledWith('삼성', 0, 10);
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage([]));
    await gotoCorporateTab();

    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('검색 실패는 사유를 남긴다', async () => {
    mockedFinancial.companies.mockRejectedValue(new Error('down'));
    await gotoCorporateTab();

    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    expect(await screen.findByText('기업 검색에 실패했습니다.')).toBeInTheDocument();
  });

  it('종목을 고르면 신용평가와 기존 대출을 함께 읽는다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([corporateLoan()] as never);
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    await userEvent.click(await screen.findByText('삼성전자'));

    expect(await screen.findByText('기업 신용평가')).toBeInTheDocument();
    expect(screen.getByText('900')).toBeInTheDocument();
    expect(screen.getByText('25.50%')).toBeInTheDocument();
    expect(screen.getByText('N/A')).toBeInTheDocument(); // roa=null
    expect(mockedLoan.corporateCredit).toHaveBeenCalledWith('005930');
  });

  it('신용평가 조회 실패는 사유를 남긴다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockRejectedValue(new Error('down'));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    await userEvent.click(await screen.findByText('삼성전자'));

    expect(await screen.findByText('기업 신용평가 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('한도를 넘는 원금을 넣으면 경고를 띄운다 (신청은 서버가 최종 판단)', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit({ limit: 5_000_000 }));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    await screen.findByText('기업 신용평가');

    expect(screen.getByText(/한도\(.*\)를 초과했습니다\./)).toBeInTheDocument();
  });

  it('기업대출을 신청하면 등급·수수료를 안내하고 목록을 다시 읽는다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.requestCorporate.mockResolvedValue(corporateLoan({ id: 21 }));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));
    await screen.findByText('기업 신용평가');

    fireEvent.click(screen.getByRole('button', { name: '기업대출 신청' }));

    await waitFor(() =>
      expect(mockedLoan.requestCorporate).toHaveBeenCalledWith({
        stockCode: '005930',
        principal: 10_000_000,
        termDays: 30,
      }),
    );
    expect(await screen.findByText(/기업대출 신청 완료 — #21 \(A/)).toBeInTheDocument();
  });

  it('신청 실패는 사유를 남긴다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.requestCorporate.mockRejectedValue(new Error('boom'));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));
    await screen.findByText('기업 신용평가');

    fireEvent.click(screen.getByRole('button', { name: '기업대출 신청' }));

    expect(await screen.findByText('기업대출 신청에 실패했습니다.')).toBeInTheDocument();
  });

  it('대출 내역이 없으면 그 사실을 알린다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    expect(await screen.findByText('해당 종목의 기업대출 내역이 없습니다.')).toBeInTheDocument();
  });

  it('DISBURSED 건은 상환 입력을 열고 미상환액을 기본값으로 채운다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([
      corporateLoan({ id: 31, status: 'DISBURSED', outstanding: 10_050_000 }),
    ] as never);
    mockedLoan.repayCorporate.mockResolvedValue(undefined as never);
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    await userEvent.click(await screen.findByRole('button', { name: '상환' }));
    const amountInput = screen.getByPlaceholderText('상환액') as HTMLInputElement;
    expect(amountInput.value).toBe('10050000');

    await userEvent.click(screen.getByRole('button', { name: '확인' }));

    await waitFor(() => expect(mockedLoan.repayCorporate).toHaveBeenCalledWith(31, 10_050_000));
    expect(await screen.findByText(/#31 기업대출 상환 완료/)).toBeInTheDocument();
  });

  it('상환 입력을 취소하면 버튼 상태로 돌아간다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([
      corporateLoan({ id: 31, status: 'DISBURSED' }),
    ] as never);
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));
    await userEvent.click(await screen.findByRole('button', { name: '상환' }));

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByPlaceholderText('상환액')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '상환' })).toBeInTheDocument();
  });

  it('기업대출 실행도 목록을 다시 읽는다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([corporateLoan({ id: 41 })] as never);
    mockedLoan.disburseCorporate.mockResolvedValue(undefined as never);
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    await userEvent.click(await screen.findByRole('button', { name: '실행(선지급)' }));

    await waitFor(() => expect(mockedLoan.disburseCorporate).toHaveBeenCalledWith(41));
    expect(await screen.findByText('#41 기업대출 실행(선지급) 완료')).toBeInTheDocument();
  });

  it('실행 실패는 사유를 남긴다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([corporateLoan({ id: 41 })] as never);
    mockedLoan.disburseCorporate.mockRejectedValue(new Error('boom'));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    await userEvent.click(await screen.findByRole('button', { name: '실행(선지급)' }));

    expect(await screen.findByText('기업대출 실행에 실패했습니다.')).toBeInTheDocument();
  });

  it('종결 상태는 조작 버튼 대신 -를 보여 준다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit());
    mockedLoan.corporateByStock.mockResolvedValue([
      corporateLoan({ id: 51, status: 'REPAID' }),
    ] as never);
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    const row = (await screen.findByText('#51')).closest('tr') as HTMLElement;
    expect(within(row).getByText('-')).toBeInTheDocument();
  });

  it('평판 등급이 있으면 함께 보여 준다', async () => {
    mockedFinancial.companies.mockResolvedValue(companyPage());
    mockedLoan.corporateCredit.mockResolvedValue(credit({ reputationGrade: 'BBB' }));
    await gotoCorporateTab();
    await userEvent.click(screen.getByRole('button', { name: '검색' }));
    await userEvent.click(await screen.findByText('삼성전자'));

    expect(await screen.findByText('BBB')).toBeInTheDocument();
  });
});
