import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CompanyLookupPage from '@/pages/CompanyLookupPage';
import { companyApi } from '@/api/company';

vi.mock('@/api/company', () => ({
  companyApi: {
    companies: vi.fn(),
    reputation: vi.fn(),
    articles: vi.fn(),
    documents: vi.fn(),
    documentDownloadUrl: (id: number) => `/api/company/documents/${id}/download`,
  },
}));

const mocked = vi.mocked(companyApi);

const company = { stockCode: '005930', name: '삼성전자', market: 'KOSPI' };

const companyPage = (over: Record<string, unknown> = {}) =>
  ({ content: [company], page: 0, size: 15, totalElements: 1, totalPages: 1, ...over }) as never;

const reputation = (over: Record<string, unknown> = {}) =>
  ({
    stockCode: '005930',
    grade: 'B',
    score: 72,
    snapshotDate: '2026-08-14',
    articleCount: 20,
    positiveCount: 12,
    neutralCount: 5,
    negativeCount: 3,
    negativeByCategory: { LAWSUIT: 2 },
    ...over,
  }) as never;

const articles = (content: Record<string, unknown>[] = []) => ({ content }) as never;

const document_ = (over: Record<string, unknown> = {}) =>
  ({
    id: 11,
    title: '2026Q2 CEO 브리핑',
    fileName: 'briefing.docx',
    contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    sizeBytes: 20480,
    uploadedAt: '2026-08-10T00:00:00Z',
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.companies.mockResolvedValue(companyPage());
  mocked.reputation.mockResolvedValue(reputation());
  mocked.articles.mockResolvedValue(
    articles([
      {
        title: '삼성전자 2분기 실적 발표',
        summary: '영업이익 증가',
        url: 'https://news.example.com/1',
        publisher: '한국경제',
        publishedAt: '2026-08-12T00:00:00Z',
      },
    ]),
  );
  mocked.documents.mockResolvedValue([document_()] as never);
});

const openCompany = async () => {
  render(<CompanyLookupPage />);
  await screen.findByText('삼성전자');
  await userEvent.click(screen.getByRole('button', { name: '뉴스·평판 보기' }));
};

describe('CompanyLookupPage — 목록', () => {
  it('기업 목록을 읽어 보여 준다', async () => {
    render(<CompanyLookupPage />);

    expect(await screen.findByText('삼성전자')).toBeInTheDocument();
    expect(mocked.companies).toHaveBeenCalledWith('', 0);
  });

  it('검색은 첫 페이지부터 다시 조회한다', async () => {
    render(<CompanyLookupPage />);
    await screen.findByText('삼성전자');

    await userEvent.type(screen.getByPlaceholderText(/기업명 또는 종목코드 검색/), '삼성');
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mocked.companies).toHaveBeenLastCalledWith('삼성', 0));
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    mocked.companies.mockResolvedValue(companyPage({ content: [], totalElements: 0 }));
    render(<CompanyLookupPage />);

    expect(await screen.findByText('검색 결과가 없습니다')).toBeInTheDocument();
  });

  it('목록 조회 실패는 사유를 보여 준다', async () => {
    mocked.companies.mockRejectedValue(new Error('down'));
    render(<CompanyLookupPage />);

    expect(await screen.findByText('기업 목록 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('여러 페이지면 다음 페이지를 요청할 수 있다', async () => {
    mocked.companies.mockResolvedValue(companyPage({ totalElements: 30, totalPages: 2 }));
    render(<CompanyLookupPage />);
    await screen.findByText('삼성전자');

    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() => expect(mocked.companies).toHaveBeenLastCalledWith('', 1));
  });
});

describe('CompanyLookupPage — 상세', () => {
  it('평판·문서·기사를 함께 읽어 보여 준다', async () => {
    await openCompany();

    expect(await screen.findByText('B')).toBeInTheDocument();
    expect(screen.getByText('72')).toBeInTheDocument();
    expect(screen.getByText('긍정 12')).toBeInTheDocument();
    expect(screen.getByText('부정 3')).toBeInTheDocument();
    expect(screen.getByText('2026Q2 CEO 브리핑')).toBeInTheDocument();
    expect(screen.getByText('삼성전자 2분기 실적 발표')).toBeInTheDocument();
  });

  it('기사 링크는 원문으로 새 창을 연다 (본문은 저장하지 않는다)', async () => {
    await openCompany();

    const link = await screen.findByRole('link', { name: '삼성전자 2분기 실적 발표' });
    expect(link).toHaveAttribute('href', 'https://news.example.com/1');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('문서 다운로드 링크는 문서 ID 경로를 쓴다', async () => {
    await openCompany();

    const link = await screen.findByRole('link', { name: /2026Q2 CEO 브리핑/ });
    expect(link).toHaveAttribute('href', '/api/company/documents/11/download');
  });

  it('평판이 아직 없으면 재계산 필요를 알린다', async () => {
    mocked.reputation.mockResolvedValue(null as never);
    await openCompany();

    expect(
      await screen.findByText('아직 평판이 산정되지 않았습니다 (수집된 기사로 재계산 필요).'),
    ).toBeInTheDocument();
  });

  it('문서가 없으면 그 사실을 알린다', async () => {
    mocked.documents.mockResolvedValue([] as never);
    await openCompany();

    expect(await screen.findByText('등록된 브리핑 문서가 없습니다.')).toBeInTheDocument();
  });

  it('문서 조회가 실패해도(권한 없음 등) 나머지 상세는 그대로 보여 준다', async () => {
    mocked.documents.mockRejectedValue({ response: { status: 403 } });
    await openCompany();

    expect(await screen.findByText('등록된 브리핑 문서가 없습니다.')).toBeInTheDocument();
    expect(screen.getByText('삼성전자 2분기 실적 발표')).toBeInTheDocument();
  });

  it('기사가 없으면 그 사실을 알린다', async () => {
    mocked.articles.mockResolvedValue(articles([]));
    await openCompany();

    expect(await screen.findByText('수집된 기사가 없습니다.')).toBeInTheDocument();
  });

  it('상세 조회 실패는 사유를 보여 준다', async () => {
    mocked.reputation.mockRejectedValue(new Error('down'));
    await openCompany();

    expect(await screen.findByText('기업 상세 조회에 실패했습니다.')).toBeInTheDocument();
  });

  it('닫기를 누르면 상세를 접는다', async () => {
    await openCompany();
    await screen.findByText('평판');

    await userEvent.click(screen.getByRole('button', { name: '닫기 ✕' }));

    expect(screen.queryByText('평판')).not.toBeInTheDocument();
  });
});
