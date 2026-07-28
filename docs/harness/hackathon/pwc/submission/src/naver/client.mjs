/**
 * 네이버 뉴스 검색 OpenAPI 클라이언트 (zero-dependency, Node 18+).
 *
 * 인증: X-Naver-Client-Id / X-Naver-Client-Secret 헤더 (env NAVER_CLIENT_ID,
 * NAVER_CLIENT_SECRET). 키가 env 에 없으면 상위 디렉터리의 .env 에서 폴백한다.
 *
 * 이 클라이언트는 기사 본문 전문을 수집하지 않는다. 공식 검색 API가 반환하는
 * 제목, 요약, 링크, 발행일 메타데이터만 CEO 리스크 분석 입력으로 정규화한다.
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const BASE = 'https://openapi.naver.com/v1/search/news.json';
const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const REQUEST_TIMEOUT_MS = 20_000;

export const CLIENT_ID = findEnvKey('NAVER_CLIENT_ID', MODULE_DIR);
export const CLIENT_SECRET = findEnvKey('NAVER_CLIENT_SECRET', MODULE_DIR);

export function requireKeys() {
  if (!CLIENT_ID || !CLIENT_SECRET) {
    throw new Error('NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 이 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://developers.naver.com)');
  }
}

function clampInt(value, { min, max, fallback }) {
  const n = Number(value);
  if (!Number.isInteger(n)) return fallback;
  return Math.max(min, Math.min(n, max));
}

function normalizeSort(sort) {
  return sort === 'sim' || sort === 'date' ? sort : 'date';
}

function decodeHtml(text) {
  return String(text ?? '')
    .replace(/<[^>]*>/g, '')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .trim();
}

function normalizeItem(item) {
  const original = String(item.originallink ?? '').trim();
  const naver = String(item.link ?? '').trim();
  return {
    title: decodeHtml(item.title),
    description: decodeHtml(item.description),
    url: original || naver,
    naverUrl: naver || original,
    pubDate: String(item.pubDate ?? '').trim(),
  };
}

export async function searchNews({ query, display = 10, start = 1, sort = 'date' }) {
  requireKeys();
  const q = String(query ?? '').trim();
  if (!q) throw new Error('query 는 비어 있을 수 없습니다');

  const params = new URLSearchParams({
    query: q,
    display: String(clampInt(display, { min: 1, max: 100, fallback: 10 })),
    start: String(clampInt(start, { min: 1, max: 1000, fallback: 1 })),
    sort: normalizeSort(sort),
  });
  const res = await fetch(`${BASE}?${params}`, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: {
      'X-Naver-Client-Id': CLIENT_ID,
      'X-Naver-Client-Secret': CLIENT_SECRET,
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const code = body?.errorCode ? ` ${body.errorCode}` : '';
    const message = body?.errorMessage ? `: ${body.errorMessage}` : '';
    throw new Error(`NAVER news → HTTP ${res.status}${code}${message}`);
  }

  return {
    query: q,
    total: Number(body.total ?? 0),
    start: Number(body.start ?? 0),
    display: Number(body.display ?? 0),
    sort: normalizeSort(sort),
    items: (body.items ?? []).map(normalizeItem),
  };
}

/**
 * 법인 접미사 제거 — "신성통상(주)"·"㈜한빛"·"주식회사 대상" → "신성통상"·"한빛"·"대상".
 * DART corp_name 을 그대로 쿼리에 쓰면 "(주)+키워드 AND" 조합이 0건으로 떨어지는 문제의 원인.
 */
export function cleanCompanyName(company) {
  const cleaned = String(company ?? '')
    .replace(/주식회사|\(주\)|㈜|\(유\)|\(합\)/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned || String(company ?? '').trim();
}

export function buildCompanyQuery({ company, keywords = [] }) {
  const name = cleanCompanyName(company);
  if (!name) throw new Error('company 는 비어 있을 수 없습니다');
  const parts = [name, ...keywords.map((k) => String(k).trim()).filter(Boolean)];
  return parts.join(' ');
}

export function searchCompanyNews({ company, keywords = [], display = 10, start = 1, sort = 'date' }) {
  return searchNews({
    query: buildCompanyQuery({ company, keywords }),
    display,
    start,
    sort,
  });
}
