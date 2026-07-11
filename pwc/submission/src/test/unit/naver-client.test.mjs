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

test('cleanCompanyName — 법인 접미사 제거: (주)·㈜·주식회사·(유)', () => {
  assert.equal(naver.cleanCompanyName('신성통상(주)'), '신성통상');
  assert.equal(naver.cleanCompanyName('㈜한빛커머스'), '한빛커머스');
  assert.equal(naver.cleanCompanyName('주식회사 대상'), '대상');
  assert.equal(naver.cleanCompanyName('삼성물산 주식회사'), '삼성물산');
  assert.equal(naver.cleanCompanyName('한빛(유)'), '한빛');
  assert.equal(naver.cleanCompanyName('  PFCT  '), 'PFCT');       // 접미사 없으면 trim 만
  assert.equal(naver.cleanCompanyName('(주)'), '(주)');           // 제거 후 빈 문자열이면 원문 유지
});

test('searchCompanyNews — 법인 접미사를 떼고 검색 (키워드 0건 문제 방지)', async () => {
  stubFetch(() => jsonRes({ total: 0, start: 1, display: 0, items: [] }));
  await naver.searchCompanyNews({ company: '신성통상(주)', keywords: ['평판'], display: 10 });
  // query=신성통상+평판 — "(주)" 가 쿼리에 남아 있으면 안 된다
  assert.match(lastRequest.url, /query=%EC%8B%A0%EC%84%B1%ED%86%B5%EC%83%81\+%ED%8F%89%ED%8C%90/);
  assert.ok(!decodeURIComponent(lastRequest.url).includes('(주)'));
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
