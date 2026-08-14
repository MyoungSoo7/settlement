import { describe, it, expect, vi, beforeEach } from 'vitest';
import { buildCeoBriefing, ceoApi, pickLatestStatement } from '@/api/ceo';
import { companyApi } from '@/api/company';
import { economicsApi } from '@/api/economics';
import { financialApi } from '@/api/financial';
import { marketApi } from '@/api/market';

vi.mock('@/api/company', () => ({
  companyApi: { reputation: vi.fn(), articles: vi.fn(), documents: vi.fn(), companies: vi.fn() },
}));
vi.mock('@/api/economics', () => ({ economicsApi: { indicators: vi.fn() } }));
vi.mock('@/api/financial', () => ({ financialApi: { statements: vi.fn(), companies: vi.fn() } }));
vi.mock('@/api/market', () => ({ marketApi: { latest: vi.fn() } }));

const company = { stockCode: '005930', name: '삼성전자', corpCode: '00126380' } as never;

const statement = {
  fiscalYear: 2026, fsDivision: 'CFS',
  revenue: 300_000_000_000, operatingProfit: 20_000_000_000, netIncome: 10_000_000_000,
  totalAssets: 500_000_000_000, totalLiabilities: 200_000_000_000, totalEquity: 300_000_000_000,
  operatingMargin: 6.7, netMargin: 3.3, debtRatio: 66.7, equityRatio: 60, roa: 2, source: 'DART',
};

const emptyPage = { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(financialApi.statements).mockResolvedValue([statement] as never);
  vi.mocked(companyApi.reputation).mockResolvedValue({ score: 71 } as never);
  vi.mocked(companyApi.articles).mockResolvedValue({ ...emptyPage, content: [{ id: 1 }] } as never);
  vi.mocked(companyApi.documents).mockResolvedValue([{ id: 9 }] as never);
  vi.mocked(companyApi.companies).mockResolvedValue({ ...emptyPage, content: [company] } as never);
  vi.mocked(economicsApi.indicators).mockResolvedValue([] as never);
  vi.mocked(marketApi.latest).mockResolvedValue({ latest: { marketCap: 600_000_000_000 } } as never);
});

/**
 * CEO 인사이트 조립 — 핵심은 <b>부분 실패를 브리핑 실패로 만들지 않는다</b>는 것이다.
 *
 * <p>평판·기사·문서함·시세는 각각 다른 서비스(company·market)에서 오고, 미상장·미적재·수집 지연이
 * 일상적으로 발생한다. 그 중 하나가 죽었다고 화면 전체가 비면 CEO 는 볼 수 있는 것도 못 본다.
 * 그래서 각 호출에 개별 폴백이 걸려 있고, 이 테스트가 그 계약을 고정한다.
 */
describe('ceoApi.insight — 조립과 폴백', () => {
  it('여섯 소스를 모아 브리핑까지 만들어 준다', async () => {
    const insight = await ceoApi.insight(company);

    expect(insight.latestStatement?.fiscalYear).toBe(2026);
    expect(insight.reputation).toEqual({ score: 71 });
    expect(insight.articles).toHaveLength(1);
    expect(insight.documents).toHaveLength(1);
    expect(insight.companyProfile).toEqual(company);
    expect(insight.briefing).toBeTruthy();
    // 기사 조회는 브리핑용 5건만 — 목록 화면과 다른 크기다
    expect(companyApi.articles).toHaveBeenCalledWith('005930', 0, 5);
  });

  it('시총과 순이익·자본이 있으면 PER·PBR 을 계산한다', async () => {
    const insight = await ceoApi.insight(company);

    expect(insight.valuation.per).toBeCloseTo(60, 5);
    expect(insight.valuation.pbr).toBeCloseTo(2, 5);
  });

  it('시세가 없으면(미상장·미적재) 시총 없이 브리핑을 만든다 — PER·PBR 은 N/A', async () => {
    vi.mocked(marketApi.latest).mockRejectedValue(new Error('404'));

    const insight = await ceoApi.insight(company);

    expect(insight.marketQuote).toBeNull();
    expect(insight.valuation.per).toBeNull();
    expect(insight.valuation.pbr).toBeNull();
    expect(insight.briefing).toBeTruthy();
  });

  it('평판·기사·문서함이 모두 죽어도 브리핑은 생성된다', async () => {
    vi.mocked(companyApi.reputation).mockRejectedValue(new Error('down'));
    vi.mocked(companyApi.articles).mockRejectedValue(new Error('down'));
    vi.mocked(companyApi.documents).mockRejectedValue(new Error('down'));

    const insight = await ceoApi.insight(company);

    expect(insight.reputation).toBeNull();
    expect(insight.articles).toEqual([]);
    expect(insight.documents).toEqual([]);
    expect(insight.briefing).toBeTruthy();
  });

  it('기업 프로필 조회가 실패하거나 종목이 안 맞으면 null 로 둔다', async () => {
    vi.mocked(companyApi.companies).mockRejectedValue(new Error('down'));
    expect((await ceoApi.insight(company)).companyProfile).toBeNull();

    vi.mocked(companyApi.companies).mockResolvedValue(
      { ...emptyPage, content: [{ ...company, stockCode: '000660' }] } as never);
    expect((await ceoApi.insight(company)).companyProfile).toBeNull();
  });

  it('재무제표가 없으면 최신 재무는 null 이고 밸류에이션도 비운다', async () => {
    vi.mocked(financialApi.statements).mockResolvedValue([] as never);

    const insight = await ceoApi.insight(company);

    expect(insight.latestStatement).toBeNull();
    expect(insight.valuation.per).toBeNull();
    expect(insight.valuation.pbr).toBeNull();
  });
});

/**
 * 같은 회계연도에 연결(CFS)과 별도(OFS)가 함께 오는 것은 상장사에서 정상이다.
 * 어느 쪽을 "최신"으로 볼지가 브리핑 숫자를 통째로 바꾸므로 동률 규칙을 못 박는다.
 */
describe('pickLatestStatement — 동률 처리', () => {
  it('같은 연도면 연결(CFS)이 별도(OFS)를 이긴다', () => {
    const picked = pickLatestStatement([
      { ...statement, fsDivision: 'OFS' },
      { ...statement, fsDivision: 'CFS' },
    ]);

    expect(picked?.fsDivision).toBe('CFS');
  });

  it('연도·구분이 모두 같으면 순서를 뒤집지 않는다 (안정 정렬)', () => {
    const first = { ...statement, revenue: 111 };
    const second = { ...statement, revenue: 222 };

    expect(pickLatestStatement([first, second])?.revenue).toBe(111);
  });
});

describe('buildCeoBriefing — 지표 표기', () => {
  it('관측값이 아직 없는 지표는 N/A 로 적는다 — 0 으로 적으면 거짓말이 된다', () => {
    const briefing = buildCeoBriefing({
      companyName: '삼성전자',
      statement,
      reputation: null,
      indicators: [{ code: 'BASE_RATE', name: '기준금리', unit: '%', latest: null, change: null }],
    });

    const rateCard = briefing.summaryCards.find((card) => card.label === '경제환경');
    expect(rateCard?.value).toBe('N/A');
    // 지표 자체는 잡혔다는 근거 — 카드가 비어서 N/A 인 것과 구분한다
    expect(rateCard?.hint).toBe('기준금리');
  });
});
