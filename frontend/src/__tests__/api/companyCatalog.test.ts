import { describe, it, expect, vi, beforeEach } from 'vitest';
import { companyApi } from '@/api/company';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), defaults: { baseURL: 'https://api.example.test' } },
}));

const mocked = vi.mocked(api);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue({ status: 200, data: {} } as never);
});

/** 첫 번째 GET 호출의 URL. */
const calledUrl = () => String(mocked.get.mock.calls[0][0]);

/**
 * 기업 조회 API 클라이언트 계약 — 쿼리 조립과 <b>204 처리</b>가 실제 로직이다.
 *
 * <p>평판은 아직 산정되지 않은 기업이 흔해서 서버가 204(본문 없음)를 준다. 그걸 그대로
 * 흘리면 화면이 빈 문자열을 객체처럼 읽어 깨지므로, 클라이언트가 null 로 정규화한다.
 */
describe('companyApi — 기업 조회', () => {
  it('검색어가 있으면 keyword 로 싣고, 앞뒤 공백은 떼어 낸다', async () => {
    await companyApi.companies('  삼성  ', 2, 10);

    expect(calledUrl()).toBe('/api/company/companies?page=2&size=10&keyword=%EC%82%BC%EC%84%B1');
  });

  it('검색어가 공백뿐이면 keyword 를 아예 싣지 않는다 — 전체 목록 조회다', async () => {
    await companyApi.companies('   ', 0);

    expect(calledUrl()).toBe('/api/company/companies?page=0&size=15');
  });

  it('평판 미산정(204)은 null 로 정규화한다', async () => {
    mocked.get.mockResolvedValue({ status: 204, data: '' } as never);

    await expect(companyApi.reputation('005930')).resolves.toBeNull();
  });

  it('200 이어도 본문이 비어 있으면 null 이다 — 프록시가 204 를 200 으로 바꿔 놓는 경우가 있다', async () => {
    mocked.get.mockResolvedValue({ status: 200, data: '' } as never);

    await expect(companyApi.reputation('005930')).resolves.toBeNull();
  });

  it('평판이 있으면 그대로 돌려준다', async () => {
    mocked.get.mockResolvedValue({ status: 200, data: { score: 71 } } as never);

    await expect(companyApi.reputation('005930')).resolves.toEqual({ score: 71 });
    expect(calledUrl()).toBe('/api/company/companies/005930/reputation');
  });

  it('기사 목록은 페이지 기본값(0, 20)을 쿼리로 싣는다', async () => {
    await companyApi.articles('005930');

    expect(calledUrl()).toBe('/api/company/companies/005930/articles?page=0&size=20');
  });

  it('문서함 목록은 종목코드 경로만 친다', async () => {
    mocked.get.mockResolvedValue({ status: 200, data: [] } as never);

    await companyApi.documents('005930');

    expect(calledUrl()).toBe('/api/company/companies/005930/documents');
  });

  it('문서 다운로드는 baseURL 을 붙인 절대 URL 이다 — <a href> 로 바로 쓴다', () => {
    expect(companyApi.documentDownloadUrl(42))
      .toBe('https://api.example.test/api/company/documents/42/download');
  });
});
