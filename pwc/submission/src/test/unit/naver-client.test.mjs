import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.NAVER_CLIENT_ID = 'test-client';
process.env.NAVER_CLIENT_SECRET = 'test-secret';
const naver = await import('../../naver/client.mjs');

let lastRequest = {};
function stubFetch(responder) {
  globalThis.fetch = async (url, options = {}) => {
    lastRequest = { url: String(url), options };
    return responder(lastRequest);
  };
}
const jsonRes = (body, status = 200) => new Response(JSON.stringify(body), { status });

test('API keys — env 키 인식', () => {
  assert.equal(naver.CLIENT_ID, 'test-client');
  assert.equal(naver.CLIENT_SECRET, 'test-secret');
  naver.requireKeys();
});

test('searchNews — 네이버 뉴스 결과를 CEO 리스크 분석용 메타데이터로 정규화', async () => {
  stubFetch(() => jsonRes({
    total: 2,
    start: 1,
    display: 2,
    items: [
      {
        title: '<b>PFCT</b>, 금융사 제휴 확대',
        originallink: 'https://news.example.com/original',
        link: 'https://n.news.naver.com/article/001',
        description: 'AI 신용평가 모델을 <b>확대</b>한다.',
        pubDate: 'Mon, 06 Jul 2026 09:00:00 +0900',
      },
      {
        title: 'PFCT 규제 대응 조직 강화',
        link: 'https://n.news.naver.com/article/002',
        description: '컴플라이언스 인력을 채용한다.',
        pubDate: 'Tue, 07 Jul 2026 10:30:00 +0900',
      },
    ],
  }));

  const result = await naver.searchNews({ query: 'PFCT 핀테크', display: 50, start: 1, sort: 'date' });

  assert.match(lastRequest.url, /^https:\/\/openapi\.naver\.com\/v1\/search\/news\.json\?/);
  assert.match(lastRequest.url, /query=PFCT\+%ED%95%80%ED%85%8C%ED%81%AC/);
  assert.match(lastRequest.url, /display=50/);
  assert.equal(lastRequest.options.headers['X-Naver-Client-Id'], 'test-client');
  assert.equal(lastRequest.options.headers['X-Naver-Client-Secret'], 'test-secret');
  assert.equal(result.total, 2);
  assert.equal(result.items[0].title, 'PFCT, 금융사 제휴 확대');
  assert.equal(result.items[0].description, 'AI 신용평가 모델을 확대한다.');
  assert.equal(result.items[0].url, 'https://news.example.com/original');
  assert.equal(result.items[0].naverUrl, 'https://n.news.naver.com/article/001');
  assert.equal(result.items[1].url, 'https://n.news.naver.com/article/002');
});

test('searchCompanyNews — 기업명과 선택 키워드를 검색어로 결합', async () => {
  stubFetch(() => jsonRes({ total: 0, start: 1, display: 0, items: [] }));
  await naver.searchCompanyNews({ company: 'PFCT', keywords: ['규제', '투자유치'], display: 10 });
  assert.match(lastRequest.url, /query=PFCT\+%EA%B7%9C%EC%A0%9C\+%ED%88%AC%EC%9E%90%EC%9C%A0%EC%B9%98/);
  assert.match(lastRequest.url, /display=10/);
});

test('searchNews — display/start 범위와 sort 값을 보정', async () => {
  stubFetch(() => jsonRes({ total: 0, start: 1, display: 0, items: [] }));
  await naver.searchNews({ query: 'PFCT', display: 999, start: -5, sort: 'bad' });
  assert.match(lastRequest.url, /display=100/);
  assert.match(lastRequest.url, /start=1/);
  assert.match(lastRequest.url, /sort=date/);
});

test('searchNews — 네이버 API 오류 응답은 throw', async () => {
  stubFetch(() => jsonRes({ errorCode: 'SE01', errorMessage: '잘못된 쿼리' }, 400));
  await assert.rejects(() => naver.searchNews({ query: 'PFCT' }), /HTTP 400.*SE01.*잘못된 쿼리/);
});
