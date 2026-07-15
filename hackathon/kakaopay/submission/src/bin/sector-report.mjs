#!/usr/bin/env node
/**
 * 산업군×시기 규칙 충족도 리포트 CLI — 사전계산 매트릭스(sector-matrix.json)에
 * "현재 시점" 뉴스 악재 스캔을 얹어 .docx 리포트를 outputs/ 에 저장한다.
 *
 * 실행: node src/bin/sector-report.mjs               (매트릭스 캐시 사용, 뉴스 라이브)
 *       node src/bin/sector-report.mjs --refresh     (재무 매트릭스부터 재계산)
 *       node src/bin/sector-report.mjs --no-news     (뉴스 축 생략 — NAVER 키 없이)
 *       node src/bin/sector-report.mjs --limit 8     (--refresh 와 함께 빠른 확인용)
 *
 * 산출: outputs/sector-suitability-<YYYY-MM-DD-HHmm>.docx (기존 파일 덮어쓰지 않음)
 *
 * 정직성 원칙: 뉴스 검색은 소급 조회가 안 된다 — 재무 축만 시기별이고,
 * 뉴스 열은 "지금 기준 최근 30일" 한 칸이다. 리포트에도 그렇게 명시한다.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { computeSectorMatrix, STATS_PATH } from './sector-matrix.mjs';
import { searchCompanyNews, cleanCompanyName, CLIENT_ID, CLIENT_SECRET } from '../naver/client.mjs';
import { RULES, trendArrow, formatCell } from '../common/sector-rules.mjs';
import { buildDocx } from '../common/docx.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const outputsDir = join(here, '..', '..', 'outputs');

const args = process.argv.slice(2);
const hasFlag = f => args.includes(f);
const limitArg = args.indexOf('--limit');
const limit = limitArg >= 0 ? Number(args[limitArg + 1]) : Infinity;

// 초보 투자자가 놓치면 치명적인 고위험 악재만 스캔 (news-server 기본 키워드의 상위 3종)
const NEWS_RISK_KEYWORDS = ['유상증자', '횡령', '거래정지'];
const NEWS_WINDOW_DAYS = 30;
const NEWS_CALL_DELAY_MS = 120;
const NEWS_PER_KEYWORD = 5;

// ── 1) 재무 매트릭스 (캐시 우선) ────────────────────────────────────────────
async function loadMatrix() {
  if (!hasFlag('--refresh') && existsSync(STATS_PATH)) {
    const cached = JSON.parse(readFileSync(STATS_PATH, 'utf8'));
    console.log(`매트릭스 캐시 사용: ${cached.generatedAt} (재계산: --refresh)`);
    return cached;
  }
  console.log('재무 매트릭스 계산 중... (DART 요약재무, 종목×시기 순차 조회)');
  const matrix = await computeSectorMatrix({ limit, log: console.log });
  mkdirSync(dirname(STATS_PATH), { recursive: true });
  writeFileSync(STATS_PATH, JSON.stringify(matrix, null, 2) + '\n');
  return matrix;
}

// ── 2) 뉴스 악재 스캔 (현재 시점 최근 30일 — 소급 불가를 정직하게 명시) ─────
async function scanNews(matrix) {
  if (hasFlag('--no-news')) return { skipped: '옵션(--no-news)으로 생략', hitsBySector: {}, failuresBySector: {}, failedCalls: 0, totalCalls: 0 };
  if (!CLIENT_ID || !CLIENT_SECRET) return { skipped: 'NAVER 키 없음 — 뉴스 축 생략 (env NAVER_CLIENT_ID/SECRET)', hitsBySector: {}, failuresBySector: {}, failedCalls: 0, totalCalls: 0 };

  const since = Date.now() - NEWS_WINDOW_DAYS * 86_400_000;
  const hitsBySector = {};
  // 스캔 "실패"와 "스캔했으나 깨끗"은 다른 결과다 — 실패를 삼키고 '신호 없음'으로
  // 표기하면 거짓 음성이 된다. 섹터별 실패 콜 수를 집계해 리포트에 함께 표기한다.
  const failuresBySector = {};
  let failedCalls = 0;
  const totalCalls = matrix.stocks.length * NEWS_RISK_KEYWORDS.length;
  console.log(`뉴스 악재 스캔 중... (${matrix.stocks.length}종목 × ${NEWS_RISK_KEYWORDS.length}키워드 = ${totalCalls}콜)`);

  let done = 0;
  for (const stock of matrix.stocks) {
    const shortName = cleanCompanyName(stock.name);
    for (const keyword of NEWS_RISK_KEYWORDS) {
      try {
        const result = await searchCompanyNews({
          company: stock.name, keywords: [keyword], display: NEWS_PER_KEYWORD, sort: 'date',
        });
        for (const item of result.items) {
          const published = Date.parse(item.pubDate);
          // 30일 이내 + "제목에" 종목명과 악재 키워드가 동시에 등장하는 기사만.
          // 요약(description) 매칭까지 허용하면 두 단어가 스치듯 지나가는 시황·타사 기사가
          // 대량 유입된다(실측 365건 — 예: "뉴인텍 유상증자" 기사가 현대차 악재로 집계).
          // 제목 동시 등장도 완벽하진 않으므로 리포트는 건수 대신 "신호 종목"을 앞세운다.
          if (Number.isFinite(published) && published >= since
            && item.title.includes(shortName) && item.title.includes(keyword)) {
            (hitsBySector[stock.sector] ??= []).push({
              code: stock.code, name: stock.name, keyword,
              title: item.title, url: item.url,
              date: new Date(published).toISOString().slice(0, 10),
            });
          }
        }
      } catch (error) {
        failedCalls += 1;
        failuresBySector[stock.sector] = (failuresBySector[stock.sector] ?? 0) + 1;
        console.log(`  ! ${stock.name} ${keyword}: ${String(error.message).slice(0, 60)}`);
      }
      await delay(NEWS_CALL_DELAY_MS);
    }
    done += 1;
    if (done % 10 === 0) console.log(`  ...${done}/${matrix.stocks.length}종목`);
  }

  // 같은 기사가 키워드 2개에 걸리면 1건으로 (URL 기준 dedupe — news-server 와 동일)
  for (const sector of Object.keys(hitsBySector)) {
    const seen = new Set();
    hitsBySector[sector] = hitsBySector[sector].filter(h => !seen.has(h.url) && seen.add(h.url));
  }
  return { skipped: null, hitsBySector, failuresBySector, failedCalls, totalCalls };
}

// ── 3) docx 조립 ────────────────────────────────────────────────────────────
function newsCell(news, sector) {
  if (news.skipped) return '생략';
  const hits = news.hitsBySector[sector] ?? [];
  const failures = news.failuresBySector?.[sector] ?? 0;
  // "스캔 실패"는 "신호 없음"이 아니다 — 실패 콜이 있으면 반드시 표기한다 (거짓 음성 방지)
  if (!hits.length) return failures ? `확인 불가 (스캔 실패 ${failures}콜)` : '신호 없음';
  // 기사 수는 언론 쏠림에 비례해 부풀므로, 신호가 잡힌 "종목 수"를 앞세운다
  const stocks = new Set(hits.map(h => h.code)).size;
  return `${stocks}종목 (기사 ${hits.length}건)${failures ? ` · 스캔 실패 ${failures}콜` : ''}`;
}

function buildBlocks(matrix, news, now) {
  const periodCols = matrix.periods.map(p => p.label);
  const disclaimer = '본 리포트는 공시 재무 기준 규칙 충족 개수의 서술이며 투자자문·투자권유가 아닙니다. '
    + '투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.';

  const blocks = [
    { type: 'heading', level: 1, text: '산업군별 투자 규칙 충족도 리포트' },
    {
      type: 'para',
      text: `생성: ${now.toLocaleString('ko-KR')} · 유니버스: ${matrix.universe.name} ${matrix.universe.used}종목 `
        + `· 재무 기준: ${matrix.generatedAt.slice(0, 10)} DART 수집 · kakaopay-invest-companion`,
    },
    { type: 'para', text: '점수는 "투자적합도 예측"이 아니라 규칙 충족 개수입니다 — 산업군을 추천하는 표가 아니라, 어느 산업군이 어느 시기에 규칙을 잃었는지 보여주는 표입니다.', italic: true },

    { type: 'heading', level: 2, text: '요약 — 산업군 × 시기' },
    {
      type: 'table',
      header: ['산업군', ...periodCols, `뉴스 악재 (최근 ${NEWS_WINDOW_DAYS}일)`],
      rows: matrix.sectors.map(s => {
        const cells = [];
        let prev = null;
        for (const p of matrix.periods) {
          const agg = s.periods[p.key];
          cells.push(formatCell(agg, trendArrow(prev, agg?.avgScore5)));
          if (agg?.avgScore5 != null) prev = agg.avgScore5;
        }
        const label = s.isFinancial ? `${s.sector} †` : s.sector;
        return [`${label} (${s.stockCount})`, ...cells, newsCell(news, s.sector)];
      }),
    },
    { type: 'para', text: '▲/▼ 직전 시기 대비 ±0.3 이상 변화 · † 금융업은 부채비율 규칙 제외(4개 규칙 기준 정규화) · 괄호는 종목 수' },
    ...(news.skipped ? [{ type: 'para', text: `뉴스 열: ${news.skipped}`, color: '8A6D3B' }] : []),
    ...(news.failedCalls > 0 ? [{
      type: 'para',
      text: `⚠ 뉴스 스캔 ${news.totalCalls}콜 중 ${news.failedCalls}콜 실패 — 실패한 조회는 "신호 없음"이 아니라 "확인 불가"입니다. 해당 산업군 셀에 실패 콜 수를 표기했으며, 재실행으로 보완하세요.`,
      color: '8A6D3B',
    }] : []),

    { type: 'heading', level: 2, text: '규칙 정의 (5종)' },
    { type: 'list', items: RULES.map(r => `${r.label} — ${r.detail}`) },
    { type: 'para', text: '뉴스 축은 시기별로 제공하지 않습니다 — 뉴스 검색은 과거 시점 소급 조회가 불가능해, 지어내는 대신 "현재 기준 최근 30일" 한 칸만 제공합니다 (유상증자·횡령·거래정지 보도 중 기사 제목에 종목명과 키워드가 동시에 등장하는 것만 집계). 이 신호는 악재 확정이 아닙니다 — 제목 키워드 매칭의 한계(타사 이벤트 언급 등)가 있으므로 아래 상세의 기사 원문으로 반드시 재확인하세요.' },
  ];

  blocks.push({ type: 'heading', level: 2, text: '산업군 상세 (종목별)' });
  for (const sector of matrix.sectors) {
    const members = matrix.stocks.filter(st => st.sector === sector.sector);
    blocks.push({ type: 'heading', level: 3, text: `${sector.sector} — ${sector.stockCount}종목${sector.isFinancial ? ' (부채비율 규칙 N/A)' : ''}` });
    blocks.push({
      type: 'table',
      header: ['종목', ...periodCols],
      rows: members.map(st => [
        `${st.name} (${st.code})`,
        ...matrix.periods.map(p => {
          const r = st.periods[p.key];
          return r ? `${r.satisfied}/${r.applicable}` : '미공시';
        }),
      ]),
    });
    const hits = news.hitsBySector[sector.sector] ?? [];
    if (hits.length) {
      blocks.push({
        type: 'list',
        items: hits.slice(0, 5).map(h => `[${h.date}·${h.keyword}] ${h.name}: ${h.title}`),
      });
    }
  }

  blocks.push({ type: 'heading', level: 2, text: '방법론·한계' });
  blocks.push({ type: 'list', items: [...matrix.methodology, `뉴스: 네이버 뉴스 검색(제목·요약·링크만, 본문 미수집) — 현재 시점 최근 ${NEWS_WINDOW_DAYS}일 한정(과거 시기 소급 불가), 기사 제목의 종목명+키워드 동시 등장 기준(타사 이벤트 오집계 가능 — 원문 재확인 필수)`] });
  blocks.push({ type: 'para', text: disclaimer, bold: true });
  return blocks;
}

/** outputs/sector-suitability-<날짜-시각>.docx — 있으면 -2, -3 순번 (덮어쓰기 금지) */
function resolveOutPath(now) {
  const pad = n => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  let path = join(outputsDir, `sector-suitability-${stamp}.docx`);
  for (let seq = 2; existsSync(path); seq += 1) {
    path = join(outputsDir, `sector-suitability-${stamp}-${seq}.docx`);
  }
  return path;
}

const matrix = await loadMatrix();
const news = await scanNews(matrix);
const now = new Date();

const docx = buildDocx({ blocks: buildBlocks(matrix, news, now) }, now);
mkdirSync(outputsDir, { recursive: true });
const outPath = resolveOutPath(now);
writeFileSync(outPath, docx);

const totalHits = Object.values(news.hitsBySector).reduce((n, hits) => n + hits.length, 0);
const failNote = news.failedCalls > 0 ? ` · 스캔 실패 ${news.failedCalls}/${news.totalCalls}콜 (리포트에 "확인 불가"로 표기됨)` : '';
console.log(`\n산업군 ${matrix.sectors.length}개 × 시기 ${matrix.periods.length}개 · 뉴스 악재 ${news.skipped ? '생략' : `${totalHits}건${failNote}`}`);
console.log(`>>> 저장됨: ${outPath}`);
