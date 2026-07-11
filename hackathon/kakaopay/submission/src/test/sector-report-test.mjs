#!/usr/bin/env node
/**
 * sector-rules + docx 라이터 단위 테스트 (네트워크 없음, 결정론).
 * 실행: node src/test/sector-report-test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  parseAmount, extractAccounts, evaluateRules, aggregateSectorPeriod,
  trendArrow, formatCell, RULES,
} from '../common/sector-rules.mjs';
import { buildDocx, zipStore, crc32, xmlEscape } from '../common/docx.mjs';
import { buildPeriods } from '../bin/sector-matrix.mjs';
import { createAssert } from './smoke-harness.mjs';

const { check, finish } = createAssert();

// [1] 금액 파싱 — 콤마·음수·부재
check('parseAmount: 콤마', parseAmount('1,234,567') === 1234567);
check('parseAmount: 음수', parseAmount('-1,234') === -1234);
check('parseAmount: "-"/빈값 → null', parseAmount('-') === null && parseAmount('') === null && parseAmount(undefined) === null);

// [2] 계정 추출 — CFS 우선, 은행식 영업수익 폴백
const mkRow = (fsDiv, sjDiv, name, thstrm, frmtrm) => ({
  fs_div: fsDiv, sj_div: sjDiv, account_nm: name, thstrm_amount: thstrm, frmtrm_amount: frmtrm,
});
const cfsRows = [
  mkRow('OFS', 'IS', '매출액', '900', '800'),           // 별도 — CFS 있으면 무시돼야 함
  mkRow('CFS', 'IS', '매출액', '1,000', '800'),
  mkRow('CFS', 'IS', '영업이익', '100', '90'),
  mkRow('CFS', 'IS', '당기순이익', '70', '60'),
  mkRow('CFS', 'BS', '부채총계', '500', '450'),
  mkRow('CFS', 'BS', '자본총계', '400', '380'),
];
const acc = extractAccounts(cfsRows);
check('extractAccounts: CFS 우선', acc.basis === 'CFS' && acc.revenue.thstrm === 1000);
const bankRows = [
  mkRow('CFS', 'IS', '영업수익', '2,000', '1,900'),
  mkRow('CFS', 'IS', '영업이익', '300', '280'),
];
check('extractAccounts: 영업수익 폴백 (은행)', extractAccounts(bankRows).revenue.thstrm === 2000);
check('extractAccounts: 빈 응답 → basis null', extractAccounts([]).basis === null);

// [3] 규칙 판정 — 전 규칙 충족 / 금융업 N/A / 데이터 부재 N/A
const full = evaluateRules(acc);
check('규칙 5종 모두 판정 가능', full.applicable === 5, `got ${full.applicable}`);
check('전 규칙 충족 (성장·이익률10%·흑자·부채125%)', full.satisfied === 5, JSON.stringify(full.verdicts));

const finAcc = extractAccounts(cfsRows);
const fin = evaluateRules(finAcc, { isFinancial: true });
check('금융업: 부채비율 N/A → 분모 4', fin.applicable === 4 && fin.verdicts.debtRatio === null);

const noFrmtrm = extractAccounts([mkRow('CFS', 'IS', '매출액', '1,000', ''), mkRow('CFS', 'IS', '영업이익', '30', '')]);
const partial = evaluateRules(noFrmtrm);
check('전기 부재 → 성장 규칙 N/A (조용한 0 금지)', partial.verdicts.revenueGrowth === null && partial.verdicts.opGrowth === null);
check('이익률 3% → 미충족(false, N/A 아님)', partial.verdicts.opMargin === false);

// [4] 산업군 집계 — 정규화·미공시 처리
const agg = aggregateSectorPeriod([
  { satisfied: 5, applicable: 5 },   // 1.0
  { satisfied: 2, applicable: 4 },   // 0.5 (금융)
  { satisfied: 0, applicable: 0 },   // 미공시 — 제외
]);
check('집계: (1.0+0.5)/2 → 3.8/5', agg.avgScore5 === 3.8 && agg.ratedCount === 2 && agg.totalCount === 3, JSON.stringify(agg));
check('집계: 전원 미공시 → null', aggregateSectorPeriod([{ satisfied: 0, applicable: 0 }]).avgScore5 === null);

// [5] 추세·셀 표기
check('추세: +0.3↑ ▲ / -0.3↓ ▼ / 이내 ─', trendArrow(3.0, 3.3) === '▲' && trendArrow(3.3, 3.0) === '▼' && trendArrow(3.0, 3.2) === '─');
check('추세: 이전 미공시 → 무표기', trendArrow(null, 3.0) === '');
check('셀: 3.8/5 ▲', formatCell({ avgScore5: 3.8 }, '▲') === '3.8/5 ▲');
check('셀: 미공시', formatCell({ avgScore5: null }) === '미공시' && formatCell(null) === '미공시');

// [6] 조회 시기 — 3개 연간 + 최근 분기 (오늘 날짜 무관 구조 검증)
const periods = buildPeriods(new Date('2026-07-11'));
check('시기: 연간 3 + 분기 1', periods.length === 4 && periods.filter(p => p.reprtCode === '11011').length === 3);
check('시기: 2026-07 → 2026 1분기(11013)', periods[3].reprtCode === '11013' && periods[3].year === 2026);
check('시기: 2026-02 → 2025 3분기(11014)', buildPeriods(new Date('2026-02-01'))[3].reprtCode === '11014');

// [7] zip — CRC 표준 벡터, EOCD, stored 시그니처
check('crc32("123456789") = 0xCBF43926 (표준 벡터)', crc32(Buffer.from('123456789')) === 0xcbf43926);
const zip = zipStore([{ name: 'a.txt', data: 'hello' }], new Date('2026-07-11T12:00:00'));
check('zip: local header 시그니처', zip.readUInt32LE(0) === 0x04034b50);
check('zip: EOCD 시그니처 (꼬리 22바이트)', zip.readUInt32LE(zip.length - 22) === 0x06054b50);
check('zip: stored (무압축) — 본문 평문 포함', zip.includes(Buffer.from('hello')));

// [8] docx — 패키지 3파트 + 본문 내용 + XML 이스케이프
const docx = buildDocx({
  blocks: [
    { type: 'heading', level: 1, text: '테스트 & 리포트' },
    { type: 'para', text: '<본문>' },
    { type: 'table', header: ['산업군', '2025'], rows: [['반도체', '4.2/5 ▲']] },
    { type: 'list', items: ['항목1'] },
  ],
}, new Date('2026-07-11T12:00:00'));
const docxText = docx.toString('utf8'); // stored 라 XML 이 평문으로 보인다
for (const part of ['[Content_Types].xml', '_rels/.rels', 'word/document.xml',
  'word/_rels/document.xml.rels', 'word/styles.xml', 'docProps/core.xml', 'docProps/app.xml']) {
  check(`docx: ${part} 포함`, docxText.includes(part));
}
check('docx: 제목 & 이스케이프 (&amp;)', docxText.includes('테스트 &amp; 리포트'));
check('docx: para 이스케이프 (&lt;본문&gt;)', docxText.includes('&lt;본문&gt;'));
check('docx: 표 렌더링 (<w:tbl> + 셀 값)', docxText.includes('<w:tbl>') && docxText.includes('4.2/5 ▲'));
check('docx: tblGrid 필수 (없으면 Word 복구 대화상자 — 실측)',
  docxText.includes('<w:tblGrid><w:gridCol') && (docxText.match(/<w:gridCol /g) ?? []).length === 2);
check('docx: core.xml 제목 = 첫 heading', docxText.includes('<dc:title>테스트 &amp; 리포트</dc:title>'));
check('docx: 알 수 없는 블록 → 예외', (() => {
  try { buildDocx({ blocks: [{ type: 'nope' }] }); return false; } catch { return true; }
})());

// [9] 유니버스 — 전 종목 sector 태깅 + 금융군 선언 정합
const here = dirname(fileURLToPath(import.meta.url));
const universe = JSON.parse(readFileSync(join(here, '..', 'data', 'universe', 'krx-top.json'), 'utf8'));
check('유니버스: 전 종목 sector 보유', universe.symbols.every(s => typeof s.sector === 'string' && s.sector.length > 0));
check('유니버스: financialSectors 가 실제 존재하는 군', (universe.financialSectors ?? []).every(f => universe.symbols.some(s => s.sector === f)));
check('유니버스: 규칙 수 5 고정 (문서·MCP 설명과 동기)', RULES.length === 5);

finish();
