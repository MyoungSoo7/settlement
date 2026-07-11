/**
 * 트렌드 축 — 뉴스 언급량 시계열(기본) + 데이터랩 쇼핑인사이트(권한 있으면).
 *
 * 데이터랩 API 는 앱에 권한을 등록해야 쓸 수 있어(미등록 시 024) 기본 백엔드는
 * 뉴스 발행일 히스토그램이다: 최신순으로 기사를 페이지네이션 수집해 월별 언급량을
 * 만들고, 최근 3개월 vs 직전 3개월 비율로 방향을 판정한다. 언론 버즈는 검색·구매
 * 수요의 프록시일 뿐이므로, 수집이 기간을 전부 커버했는지(coverage)와 한계를
 * 결과에 항상 동봉한다 — 정직한 데이터가 원칙이다.
 */
import { searchNews } from './news.mjs';
import { naverPost } from './core.mjs';

const DATALAB_SHOPPING = 'https://openapi.naver.com/v1/datalab/shopping/category/keywords';
const MAX_PAGES = 8; // 100건 × 8 페이지 = 최대 800건 (API 상한 start=1000 이내)
const PAGE_DELAY_MS = 150;

function monthKey(pubDate) {
  const d = new Date(pubDate);
  if (Number.isNaN(d.getTime())) return null;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function monthsBack(now, n) {
  const d = new Date(now);
  d.setDate(1);
  d.setMonth(d.getMonth() - n);
  return d;
}

/** 뉴스 언급량 월별 히스토그램 + 방향 판정. */
export async function buzzTrend({ keyword, months = 12, now = new Date() }) {
  const kw = String(keyword ?? '').trim();
  if (!kw) throw new Error('keyword 는 비어 있을 수 없습니다');
  const span = Math.max(3, Math.min(Number(months) || 12, 24));
  const windowStart = monthsBack(now, span - 1);

  const buckets = new Map();
  let fetched = 0;
  let oldest = null;
  let coverageComplete = false;
  let marketTotal = 0;

  for (let page = 0; page < MAX_PAGES; page += 1) {
    const res = await searchNews({ query: kw, display: 100, start: page * 100 + 1, sort: 'date' });
    marketTotal = res.total;
    if (res.items.length === 0) { coverageComplete = true; break; }
    for (const item of res.items) {
      const d = new Date(item.pubDate);
      if (!Number.isNaN(d.getTime()) && (!oldest || d < oldest)) oldest = d;
      const key = monthKey(item.pubDate);
      if (key) buckets.set(key, (buckets.get(key) ?? 0) + 1);
    }
    fetched += res.items.length;
    if (oldest && oldest < windowStart) { coverageComplete = true; break; }
    if (res.items.length < 100 || fetched >= res.total) { coverageComplete = true; break; }
    await new Promise((resolve) => setTimeout(resolve, PAGE_DELAY_MS));
  }

  // 창 안의 월만, 빠진 달은 0 으로 채워 시계열 완성
  const series = [];
  for (let i = span - 1; i >= 0; i -= 1) {
    const d = monthsBack(now, i);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    series.push({ month: key, articles: buckets.get(key) ?? 0 });
  }
  // 커버리지 밖(수집이 안 닿은 과거 달)은 0 이 아니라 '미상' — null 로 구분
  if (!coverageComplete && oldest) {
    const oldestKey = monthKey(oldest);
    for (const point of series) {
      if (point.month < oldestKey) point.articles = null;
    }
  }

  const known = series.filter((p) => p.articles !== null);
  const recent3 = known.slice(-3).reduce((sum, p) => sum + p.articles, 0);
  const prior3 = known.slice(-6, -3).reduce((sum, p) => sum + p.articles, 0);
  let direction = 'insufficient-data';
  if (known.length >= 6) {
    if (prior3 === 0 && recent3 > 0) direction = 'rising-from-zero';
    else if (prior3 === 0) direction = 'flat';
    else {
      const ratio = recent3 / prior3;
      direction = ratio >= 1.3 ? 'rising' : ratio <= 0.7 ? 'falling' : 'flat';
    }
  }

  return {
    keyword: kw,
    months: span,
    series,
    recent3,
    prior3,
    direction,
    fetchedArticles: fetched,
    marketTotalArticles: marketTotal,
    coverageComplete,
    oldestFetched: oldest ? oldest.toISOString().slice(0, 10) : null,
    note: '언론 언급량은 검색·구매 수요의 프록시입니다. coverageComplete=false 이면 인기 키워드라 수집 상한(800건)이 기간을 다 못 덮은 것으로, null 인 달은 0 이 아니라 미상입니다.',
  };
}

/** 여러 키워드 버즈 비교 — 성장 방향과 최근 3개월 언급량으로 정렬. */
export async function compareBuzz({ keywords, months = 12, now = new Date() }) {
  const list = (Array.isArray(keywords) ? keywords : String(keywords ?? '').split(','))
    .map((k) => String(k).trim()).filter(Boolean).slice(0, 5);
  if (list.length < 2) throw new Error('keywords 는 2~5개가 필요합니다');
  const results = [];
  for (const kw of list) {
    results.push(await buzzTrend({ keyword: kw, months, now }));
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  const rank = { 'rising-from-zero': 3, rising: 2, flat: 1, falling: 0, 'insufficient-data': -1 };
  const ranked = [...results].sort((a, b) => (rank[b.direction] - rank[a.direction]) || (b.recent3 - a.recent3));
  return {
    months: results[0]?.months,
    ranking: ranked.map((r) => ({
      keyword: r.keyword, direction: r.direction, recent3: r.recent3, prior3: r.prior3,
      coverageComplete: r.coverageComplete,
    })),
    detail: results,
  };
}

/** 데이터랩 쇼핑인사이트 원호출 — 권한 미등록이면 core 가 구분 가능한 에러로 승격. */
export async function datalabShoppingTrend({ keywords, months = 12, timeUnit = 'month', category = '50000000', now = new Date() }) {
  const list = (Array.isArray(keywords) ? keywords : String(keywords ?? '').split(','))
    .map((k) => String(k).trim()).filter(Boolean).slice(0, 5);
  if (list.length === 0) throw new Error('keywords 는 1~5개가 필요합니다');
  const end = new Date(now);
  const start = monthsBack(end, Math.max(1, Math.min(Number(months) || 12, 24)) - 1);
  const fmt = (d) => d.toISOString().slice(0, 10);
  return naverPost(DATALAB_SHOPPING, {
    startDate: fmt(start),
    endDate: fmt(end),
    timeUnit: ['date', 'week', 'month'].includes(timeUnit) ? timeUnit : 'month',
    category: String(category),
    keyword: list.map((name) => ({ name, param: [name] })),
  });
}
