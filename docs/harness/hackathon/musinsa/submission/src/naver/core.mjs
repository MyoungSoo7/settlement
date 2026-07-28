/**
 * 네이버 OpenAPI 공용 코어 (zero-dependency, Node 18+).
 *
 * 인증: X-Naver-Client-Id / X-Naver-Client-Secret 헤더 (env NAVER_CLIENT_ID,
 * NAVER_CLIENT_SECRET). 키가 env 에 없으면 상위 디렉터리의 .env 에서 폴백한다
 * (Codex 플러그인 MCP 서버는 셸 env 를 상속하지 않음 — 실측 확인, common/env.mjs 참조).
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const REQUEST_TIMEOUT_MS = 20_000;

export const CLIENT_ID = findEnvKey('NAVER_CLIENT_ID', MODULE_DIR);
export const CLIENT_SECRET = findEnvKey('NAVER_CLIENT_SECRET', MODULE_DIR);

export function hasKeys() {
  return Boolean(CLIENT_ID && CLIENT_SECRET);
}

export function requireKeys() {
  if (!hasKeys()) {
    throw new Error('NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 이 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://developers.naver.com)');
  }
}

export function clampInt(value, { min, max, fallback }) {
  const n = Number(value);
  if (!Number.isInteger(n)) return fallback;
  return Math.max(min, Math.min(n, max));
}

export function decodeHtml(text) {
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

async function readError(res) {
  const body = await res.json().catch(() => ({}));
  const code = body?.errorCode ? ` ${body.errorCode}` : '';
  const message = body?.errorMessage ? `: ${body.errorMessage}` : '';
  return { body, summary: `HTTP ${res.status}${code}${message}` };
}

/** GET — 429 속도 제한 시 1회 백오프 재시도 (다키워드 연속 조회에서 발생). */
export async function naverGet(url, params) {
  requireKeys();
  const qs = new URLSearchParams(params);
  const doFetch = () => fetch(`${url}?${qs}`, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { 'X-Naver-Client-Id': CLIENT_ID, 'X-Naver-Client-Secret': CLIENT_SECRET },
  });
  let res = await doFetch();
  if (res.status === 429) {
    await new Promise((resolve) => setTimeout(resolve, 700));
    res = await doFetch();
  }
  if (!res.ok) {
    const { summary } = await readError(res);
    throw new Error(`NAVER GET ${url.split('/').pop()} → ${summary}`);
  }
  return res.json();
}

/** POST(JSON) — 데이터랩 계열. 권한 미등록(024) 을 구분 가능한 에러로 승격한다. */
export async function naverPost(url, payload) {
  requireKeys();
  const res = await fetch(url, {
    method: 'POST',
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: {
      'X-Naver-Client-Id': CLIENT_ID,
      'X-Naver-Client-Secret': CLIENT_SECRET,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const { body, summary } = await readError(res);
    if (body?.errorCode === '024') {
      const err = new Error(`NAVER 데이터랩 권한 미등록(024) — developers.naver.com 앱에 '데이터랩' API 를 추가하면 즉시 활성화됩니다. 그 전까지는 buzz_trend(뉴스 언급량 추이)를 트렌드 프록시로 사용하세요.`);
      err.scopeMissing = true;
      throw err;
    }
    throw new Error(`NAVER POST ${url.split('/').pop()} → ${summary}`);
  }
  return res.json();
}
