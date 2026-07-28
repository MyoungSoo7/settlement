/**
 * 네이버 쇼핑 검색 OpenAPI 클라이언트 — 상품·가격·브랜드 실체 확인 축.
 *
 * 반환 데이터는 제목/최저가/브랜드/몰/카테고리 메타데이터만 정규화한다.
 * 이 어댑터는 데모용 공개 유통 데이터 소스다 — 실서비스에서는 이 파일 하나만
 * 무신사 내부 상품 검색 API 로 교체하면 상위 도구 계약(shop_search/price_band/
 * brand_snapshot)이 그대로 유지된다.
 */
import { clampInt, decodeHtml, naverGet } from './core.mjs';

const BASE = 'https://openapi.naver.com/v1/search/shop.json';
const PAGE_DELAY_MS = 150;

function normalizeSort(sort) {
  // sim 정확도 / date 최신 / asc 저가 / dsc 고가
  return ['sim', 'date', 'asc', 'dsc'].includes(sort) ? sort : 'sim';
}

function normalizeItem(item) {
  return {
    title: decodeHtml(item.title),
    lprice: Number(item.lprice ?? 0) || null,
    hprice: Number(item.hprice ?? 0) || null,
    brand: String(item.brand ?? '').trim(),
    maker: String(item.maker ?? '').trim(),
    mallName: String(item.mallName ?? '').trim(),
    link: String(item.link ?? '').trim(),
    category: [item.category1, item.category2, item.category3, item.category4]
      .map((c) => String(c ?? '').trim()).filter(Boolean).join(' > '),
    productType: Number(item.productType ?? 0), // 1~3 일반상품(가격비교 묶임 여부), 4~6 중고, 7~9 단종/판매예정
  };
}

export async function searchShop({ query, display = 20, start = 1, sort = 'sim', filter, exclude }) {
  const q = String(query ?? '').trim();
  if (!q) throw new Error('query 는 비어 있을 수 없습니다');
  const params = {
    query: q,
    display: String(clampInt(display, { min: 1, max: 100, fallback: 20 })),
    start: String(clampInt(start, { min: 1, max: 1000, fallback: 1 })),
    sort: normalizeSort(sort),
  };
  if (filter) params.filter = String(filter);       // naverpay 등
  if (exclude) params.exclude = String(exclude);     // used:rental:cbshop
  const body = await naverGet(BASE, params);
  return {
    query: q,
    total: Number(body.total ?? 0),
    start: Number(body.start ?? 0),
    display: Number(body.display ?? 0),
    sort: params.sort,
    items: (body.items ?? []).map(normalizeItem),
  };
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const idx = (sorted.length - 1) * p;
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  return Math.round(sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo));
}

/**
 * 신품 위주(중고/렌탈/해외구매대행 제외) 최대 sample 건을 수집해 가격 분포 통계를 낸다.
 * "지금 이 세일가가 유통 시장 분포의 어디쯤인가"를 판정하는 근거.
 */
export async function priceBand({ query, sample = 100, referencePrice }) {
  const size = clampInt(sample, { min: 20, max: 200, fallback: 100 });
  const pages = Math.ceil(size / 100);
  const items = [];
  let total = 0;
  for (let page = 0; page < pages; page += 1) {
    const res = await searchShop({
      query, display: Math.min(100, size - page * 100), start: page * 100 + 1,
      sort: 'sim', exclude: 'used:rental:cbshop',
    });
    total = res.total;
    items.push(...res.items);
    if (res.items.length < 100) break;
    await new Promise((resolve) => setTimeout(resolve, PAGE_DELAY_MS));
  }

  const prices = items.map((i) => i.lprice).filter((p) => Number.isFinite(p) && p > 0).sort((a, b) => a - b);
  const stats = prices.length === 0 ? null : {
    count: prices.length,
    min: prices[0],
    p25: percentile(prices, 0.25),
    median: percentile(prices, 0.5),
    p75: percentile(prices, 0.75),
    max: prices[prices.length - 1],
  };

  let reference = null;
  if (stats && Number.isFinite(Number(referencePrice)) && Number(referencePrice) > 0) {
    const price = Number(referencePrice);
    const below = prices.filter((p) => p <= price).length;
    reference = {
      price,
      percentile: Math.round((below / prices.length) * 100),
      vsMedianPct: Math.round(((price - stats.median) / stats.median) * 1000) / 10,
    };
  }

  const mallCounts = new Map();
  for (const item of items) {
    if (item.mallName) mallCounts.set(item.mallName, (mallCounts.get(item.mallName) ?? 0) + 1);
  }
  return {
    query: String(query).trim(),
    marketTotal: total,
    sampled: items.length,
    priceStats: stats,
    reference,
    topMalls: [...mallCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5)
      .map(([mall, count]) => ({ mall, count })),
    note: '가격은 조회 시점의 판매처 표시가 기준이며 옵션/배송비에 따라 달라질 수 있습니다. 신품 위주(중고·렌탈·해외직구 제외) 표본입니다.',
  };
}

/** 브랜드명 하나로 시장 존재감 스냅샷 — 상품 수, 카테고리 분포, 카테고리별 가격대. */
export async function brandSnapshot({ brand, sample = 100 }) {
  const name = String(brand ?? '').trim();
  if (!name) throw new Error('brand 는 비어 있을 수 없습니다');
  const size = clampInt(sample, { min: 20, max: 200, fallback: 100 });
  const pages = Math.ceil(size / 100);
  const items = [];
  let total = 0;
  for (let page = 0; page < pages; page += 1) {
    const res = await searchShop({
      query: name, display: Math.min(100, size - page * 100), start: page * 100 + 1,
      sort: 'sim', exclude: 'used:rental:cbshop',
    });
    total = res.total;
    items.push(...res.items);
    if (res.items.length < 100) break;
    await new Promise((resolve) => setTimeout(resolve, PAGE_DELAY_MS));
  }

  // 검색어와 브랜드 필드가 실제로 일치하는 비율 — 낮으면 "그 이름의 브랜드 실체가 흐리다"는 신호
  const norm = (s) => String(s).replace(/\s+/g, '').toLowerCase();
  const matched = items.filter((i) => norm(i.brand).includes(norm(name)) || norm(name).includes(norm(i.brand)) && i.brand);
  const brandMatchShare = items.length ? Math.round((matched.filter((i) => i.brand).length / items.length) * 100) : 0;

  const catCounts = new Map();
  const catPrices = new Map();
  for (const item of items) {
    const cat = item.category.split(' > ').slice(0, 2).join(' > ') || '(미분류)';
    catCounts.set(cat, (catCounts.get(cat) ?? 0) + 1);
    if (item.lprice) {
      if (!catPrices.has(cat)) catPrices.set(cat, []);
      catPrices.get(cat).push(item.lprice);
    }
  }
  const categories = [...catCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
    .map(([cat, count]) => {
      const prices = (catPrices.get(cat) ?? []).sort((a, b) => a - b);
      return {
        category: cat,
        count,
        medianPrice: percentile(prices, 0.5),
        priceRange: prices.length ? [prices[0], prices[prices.length - 1]] : null,
      };
    });

  return {
    brand: name,
    marketTotal: total,
    sampled: items.length,
    brandFieldMatchSharePct: brandMatchShare,
    categories,
    sampleItems: items.slice(0, 8).map(({ title, lprice, brand: b, mallName, category }) => ({ title, lprice, brand: b, mallName, category })),
    note: '공개 유통 데이터 기준 스냅샷 — 브랜드 공식 라인업 전체가 아니라 시장에 유통 중인 상품의 표본입니다.',
  };
}
