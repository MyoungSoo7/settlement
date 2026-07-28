import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import { classifyNewsSignals, buildNewsSignalSummary } from '../../common/news-signals.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIAG_CLI = join(HERE, '..', '..', 'bin', 'diagnose-company.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

const newsItem = (title, description = '', pubDate = 'Mon, 06 Jul 2026 09:00:00 +0900') => ({
  title,
  description,
  originallink: `https://news.example.com/${encodeURIComponent(title)}`,
  link: `https://n.news.naver.com/${encodeURIComponent(title)}`,
  pubDate,
});

const fullBody = {
  status: '000',
  list: [
    { sj_div: 'IS', account_id: 'ifrs-full_Revenue', account_nm: '매출액', bfefrmtrm_amount: '800', frmtrm_amount: '900', thstrm_amount: '1000' },
    { sj_div: 'IS', account_id: 'dart_OperatingIncomeLoss', account_nm: '영업이익', bfefrmtrm_amount: '80', frmtrm_amount: '90', thstrm_amount: '100' },
    { sj_div: 'BS', account_id: 'ifrs-full_CurrentTradeReceivables', account_nm: '매출채권', bfefrmtrm_amount: '160', frmtrm_amount: '180', thstrm_amount: '200' },
    { sj_div: 'BS', account_id: 'ifrs-full_Inventories', account_nm: '재고자산', bfefrmtrm_amount: '80', frmtrm_amount: '90', thstrm_amount: '100' },
    { sj_div: 'BS', account_id: 'ifrs-full_CurrentAssets', account_nm: '유동자산', bfefrmtrm_amount: '300', frmtrm_amount: '320', thstrm_amount: '340' },
    { sj_div: 'BS', account_id: 'ifrs-full_CurrentLiabilities', account_nm: '유동부채', bfefrmtrm_amount: '200', frmtrm_amount: '200', thstrm_amount: '200' },
    { sj_div: 'BS', account_id: 'shortBorrowings', account_nm: '단기차입금', bfefrmtrm_amount: '50', frmtrm_amount: '50', thstrm_amount: '50' },
    { sj_div: 'CF', account_id: 'ifrs-full_CashFlowsFromUsedInOperatingActivities', account_nm: '영업활동현금흐름', bfefrmtrm_amount: '120', frmtrm_amount: '135', thstrm_amount: '150' },
    { sj_div: 'CF', account_id: 'ifrs-full_InterestPaidClassifiedAsOperatingActivities', account_nm: '이자지급', bfefrmtrm_amount: '2', frmtrm_amount: '2', thstrm_amount: '2' },
  ],
};

test('classifyNewsSignals maps reputation, industry, investment, business, and finance themes', () => {
  const result = classifyNewsSignals({
    company: '테스트',
    searches: [
      { query: '테스트', items: [
        { title: '테스트 브랜드 평판 하락 논란', description: '소비자 불만과 이미지 훼손 우려', pubDate: '2026-07-01' },
        { title: '패션 업계 온라인 전환 가속', description: '관심 산업 변화', pubDate: '2026-07-02' },
        { title: '테스트 신규 투자 확대', description: '설비 투자와 물류 자동화', pubDate: '2026-07-03' },
        { title: '테스트 해외 사업 진출 및 제휴', description: '사업동향 변화', pubDate: '2026-07-04' },
        { title: '테스트 영업이익 감소 전망', description: '재무동향 둔화', pubDate: '2026-07-05' },
      ] },
    ],
    maxExamplesPerCategory: 3,
  });

  assert.equal(result.enabled, true);
  assert.equal(result.categories.reputation.count, 1);
  assert.equal(result.categories.industry.count, 1);
  assert.equal(result.categories.investment.count, 1);
  assert.equal(result.categories.business.count, 1);
  assert.equal(result.categories.finance.count, 1);
  assert.ok(result.advice.some((a) => a.category === 'reputation'));
  assert.ok(result.advice.some((a) => a.crossCheck.includes('매출')));
});

test('buildNewsSignalSummary links news themes to DART signals without treating news as fact', () => {
  const summary = buildNewsSignalSummary({
    newsSignals: classifyNewsSignals({
      company: '테스트',
      searches: [{ query: '테스트', items: [{ title: '테스트 영업이익 감소 전망', description: '차입 부담 우려', pubDate: '2026-07-05' }] }],
    }),
    externalSignals: [{ id: 'E3', present: false, evidence: { interestCoverage: '3.5→2.3배' } }],
  });
  assert.match(summary, /뉴스는 확정 사실이 아니라/);
  assert.match(summary, /E3/);
});

test('diagnose-company --with-news includes newsSignals and survives live-news packet generation', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-news-diag-'));
  try {
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify({
      rules: [
        { match: 'fnlttSinglAcntAll.json', json: fullBody },
        { match: 'company.json', json: { status: '000', corp_name: '테스트(주)', stock_code: '123456', ceo_nm: '대표', bizr_no: '1234567890' } },
        { match: 'list.json', json: { status: '000', total_count: 1, list: [{ report_nm: '분기보고서', rcept_dt: '20260701' }] } },
        { match: 'search/news.json', json: {
          total: 3,
          start: 1,
          display: 3,
          items: [
            newsItem('테스트 브랜드 평판 하락 논란', '소비자 이미지 훼손 우려'),
            newsItem('테스트 신규 투자 확대', '물류 자동화 투자'),
            newsItem('테스트 영업이익 감소 전망', '재무동향 둔화'),
          ],
        } },
      ],
    }));

    const r = runNode(
      [DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--with-news', '--news-query', '테스트', '--json'],
      withFetchStub(PRELOAD, stub, {
        DART_API_KEY: 'test-key',
        NAVER_CLIENT_ID: 'test-client',
        NAVER_CLIENT_SECRET: 'test-secret',
      }),
    );
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.newsSignals.enabled, true);
    assert.equal(out.newsSignals.categories.reputation.count, 1);
    assert.equal(out.newsSignals.categories.investment.count, 1);
    assert.ok(out.newsSignals.summary.includes('뉴스는 확정 사실이 아니라'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
