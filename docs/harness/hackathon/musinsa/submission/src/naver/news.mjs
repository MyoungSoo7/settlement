/**
 * 네이버 뉴스 검색 OpenAPI 클라이언트 — 브랜드 평판·기회 신호 확인 축.
 *
 * 기사 본문은 수집하지 않는다 — 제목/요약/링크/발행일 메타데이터만 정규화한다
 * (저작권). "이 브랜드 요즘 논란 있나요?", "콜라보/팝업 소식 있나요?" 에
 * 실보도로 답하기 위한 축이며, 뉴스가 없으면 없다고 말할 근거가 된다.
 */
import { clampInt, decodeHtml, naverGet } from './core.mjs';

const BASE = 'https://openapi.naver.com/v1/search/news.json';

function normalizeSort(sort) {
  return sort === 'sim' || sort === 'date' ? sort : 'date';
}

function normalizeItem(item) {
  const original = String(item.originallink ?? '').trim();
  const naver = String(item.link ?? '').trim();
  return {
    title: decodeHtml(item.title),
    description: decodeHtml(item.description),
    url: original || naver,
    pubDate: String(item.pubDate ?? '').trim(),
  };
}

export async function searchNews({ query, display = 10, start = 1, sort = 'date' }) {
  const q = String(query ?? '').trim();
  if (!q) throw new Error('query 는 비어 있을 수 없습니다');
  const body = await naverGet(BASE, {
    query: q,
    display: String(clampInt(display, { min: 1, max: 100, fallback: 10 })),
    start: String(clampInt(start, { min: 1, max: 1000, fallback: 1 })),
    sort: normalizeSort(sort),
  });
  return {
    query: q,
    total: Number(body.total ?? 0),
    start: Number(body.start ?? 0),
    display: Number(body.display ?? 0),
    sort: normalizeSort(sort),
    items: (body.items ?? []).map(normalizeItem),
  };
}

/** 키워드 1개씩 개별 검색 후 URL 기준 병합 — 다키워드 AND 결합은 0건이 되기 쉬움(실측). */
export async function searchMerged({ base, keywords, display = 10, sort = 'date' }) {
  const perKeyword = [];
  for (const kw of keywords) {
    perKeyword.push(await searchNews({ query: `${base} ${kw}`.trim(), display, sort }));
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  const seen = new Set();
  const items = [];
  for (const search of perKeyword) {
    for (const item of search.items ?? []) {
      const key = item.url || item.title;
      if (seen.has(key)) continue;
      seen.add(key);
      items.push(item);
    }
  }
  return {
    keywords,
    perKeyword: perKeyword.map((s) => ({ query: s.query, total: s.total, itemCount: s.items?.length ?? 0 })),
    total: items.length,
    items,
  };
}
