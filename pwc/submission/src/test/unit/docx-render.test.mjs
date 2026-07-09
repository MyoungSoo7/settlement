import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { inflateRawSync } from 'node:zlib';
import { runNode } from './helpers/proc.mjs';
import { crc32, buildZip, escapeXml, parseInline, parseBlocks, briefingToDocx, extractRiskSummary } from '../../common/docx.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = join(HERE, '..', '..', 'bin', 'render-briefing-docx.mjs');

/** 테스트용 zip 리더 — central directory 를 따라 전체 엔트리를 복원하고 CRC 를 검산한다. */
function readZip(buf) {
  const eocdAt = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  assert.ok(eocdAt >= 0, 'EOCD 없음');
  const count = buf.readUInt16LE(eocdAt + 10);
  let at = buf.readUInt32LE(eocdAt + 16);
  const entries = {};
  for (let n = 0; n < count; n += 1) {
    assert.equal(buf.readUInt32LE(at), 0x02014b50, 'central 시그니처');
    const crc = buf.readUInt32LE(at + 16);
    const compSize = buf.readUInt32LE(at + 20);
    const nameLen = buf.readUInt16LE(at + 28);
    const localAt = buf.readUInt32LE(at + 42);
    const name = buf.toString('utf8', at + 46, at + 46 + nameLen);
    const localNameLen = buf.readUInt16LE(localAt + 26);
    const localExtraLen = buf.readUInt16LE(localAt + 28);
    const dataAt = localAt + 30 + localNameLen + localExtraLen;
    const raw = inflateRawSync(buf.subarray(dataAt, dataAt + compSize));
    assert.equal(crc32(raw), crc, `CRC 불일치: ${name}`);
    entries[name] = raw.toString('utf8');
    at += 46 + nameLen;
  }
  return entries;
}

const SAMPLE_MD = `# 한빛커머스 CEO 리스크 브리핑

작성 기준: 2026-07-09 실행 결과
대상 법인: 한빛커머스(주), 종목코드 123456

## 요약 결론

이번 실행에서 PRESENT 신호는 **1건**입니다.

## 1. 수익-현금 괴리

결론: 매출은 늘었지만 현금이 따라오지 않았습니다.
근거:
- 매출채권 +46.9%
  - 세부: 90일 초과 채권 집중
확신도: 가설 — 기말 전표 확인 필요.
판별 테스트: 기말 ±7일 전표 분포 대조.

| 지표 | 값 |
|---|---|
| 매출 성장률 | 11.3% |

## 확인 범위와 한계

확신도: 확인됨 — 게이트 PASS 데이터 기준.
출처: [DART](https://dart.fss.or.kr/company)

본 문서는 CEO 경영 판단 보조 자료이며 투자자문 또는 투자권유가 아닙니다.
`;

// ── 저수준: CRC32 / zip / escape ─────────────────────────────
test('docx — crc32: 표준 검증 벡터', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
  assert.equal(crc32(Buffer.alloc(0)), 0);
});

test('docx — buildZip: 멀티 엔트리 왕복 + CRC 검산 + UTF-8 파일명 플래그', () => {
  const zip = buildZip([
    { name: 'a.txt', data: '한글 내용 가나다' },
    { name: 'dir/b.xml', data: Buffer.from('<x>값</x>', 'utf8') },
  ]);
  assert.equal(zip.readUInt16LE(6) & 0x0800, 0x0800); // UTF-8 플래그
  const entries = readZip(zip);
  assert.equal(entries['a.txt'], '한글 내용 가나다');
  assert.equal(entries['dir/b.xml'], '<x>값</x>');
});

test('docx — escapeXml / parseInline', () => {
  assert.equal(escapeXml('a<b>&"\''), 'a&lt;b&gt;&amp;&quot;&apos;');
  assert.deepEqual(parseInline('굵게 **강조** 와 [링크](https://x.y) 끝'), [
    { text: '굵게 ' }, { text: '강조', bold: true }, { text: ' 와 ' },
    { text: '링크', link: 'https://x.y' }, { text: ' 끝' },
  ]);
  assert.deepEqual(parseInline('평문'), [{ text: '평문' }]);
});

test('docx — parseBlocks: 헤딩/불릿(2단)/표/문단 분류', () => {
  const blocks = parseBlocks(SAMPLE_MD);
  const types = blocks.map((b) => b.type);
  assert.ok(types.includes('h1') && types.includes('h2') && types.includes('table'));
  const bullets = blocks.filter((b) => b.type === 'bullet');
  assert.deepEqual(bullets.map((b) => b.level), [0, 1]);
  const table = blocks.find((b) => b.type === 'table');
  assert.deepEqual(table.header, ['지표', '값']);
  assert.deepEqual(table.rows, [['매출 성장률', '11.3%']]);
});

// ── 렌더러 본체 ──────────────────────────────────────────────
test('docx — briefingToDocx: OPC 구조 + 한글 무손실 + 한글 폰트 + 서식 요소', () => {
  const entries = readZip(briefingToDocx(SAMPLE_MD));
  for (const part of ['[Content_Types].xml', '_rels/.rels', 'word/document.xml',
    'word/styles.xml', 'word/numbering.xml', 'word/footer1.xml',
    'word/_rels/document.xml.rels', 'docProps/core.xml', 'docProps/app.xml']) {
    assert.ok(entries[part], `누락: ${part}`);
  }
  // 러닝 헤더: 문서 제목 + 대외비, 표지는 titlePg 로 헤더/푸터 제외
  assert.match(entries['word/header1.xml'], /한빛커머스 CEO 리스크 브리핑/);
  assert.match(entries['word/header1.xml'], /대외비/);
  assert.match(entries['word/document.xml'], /<w:titlePg\/>/);
  const doc = entries['word/document.xml'];
  // 한글이 ? 로 소실되지 않고 그대로 실려 있다 (사용자가 겪은 ???? 회귀 방지)
  assert.match(doc, /한빛커머스 CEO 리스크 브리핑/);
  assert.match(doc, /매출은 늘었지만 현금이 따라오지 않았습니다/);
  assert.ok(!/\?{3,}/.test(doc), '???? 인코딩 소실 감지');
  // 라벨 강조: "결론." 굵은 런
  assert.match(doc, /<w:b\/>.*결론\./s);
  // 확신도 색: 가설=주황, 확인됨=녹색
  assert.match(doc, /B45309/);
  assert.match(doc, /1F7A33/);
  // 표지: 대외비 + 페이지 나누기, 요약 박스 음영
  assert.match(doc, /대외비 · Confidential/);
  assert.match(doc, /<w:br w:type="page"\/>/);
  assert.match(doc, /FBF4EE/);
  // 표 렌더 + 헤더 음영
  assert.match(doc, /<w:tbl>/);
  assert.match(doc, /F2E8E0/);
  // 하이퍼링크가 rels 에 외부 모드로 등록
  assert.match(doc, /<w:hyperlink r:id="rIdLink1">/);
  assert.match(entries['word/_rels/document.xml.rels'], /dart\.fss\.or\.kr.*TargetMode="External"/);
  // 스타일: 맑은 고딕 + Heading1 액센트 하단 괘선
  assert.match(entries['word/styles.xml'], /Malgun Gothic/);
  assert.match(entries['word/styles.xml'], /Heading1/);
  // 푸터: 면책 + PAGE 필드
  assert.match(entries['word/footer1.xml'], /투자자문·투자권유가 아닙니다/);
  assert.match(entries['word/footer1.xml'], /PAGE/);
  // 문서 메타: 제목/작성자
  assert.match(entries['docProps/core.xml'], /<dc:title>한빛커머스 CEO 리스크 브리핑<\/dc:title>/);
});

test('docx — extractRiskSummary: 리스크 섹션만 추출, 요약/한계/출처 절 제외', () => {
  const blocks = parseBlocks(SAMPLE_MD).filter((b) => !(b.type === 'h1'));
  const risks = extractRiskSummary(blocks);
  assert.equal(risks.length, 1); // "확인 범위와 한계" 는 제외
  assert.equal(risks[0].title, '수익-현금 괴리');
  assert.equal(risks[0].confidence, '가설');
});

test('docx — Executive Summary 표: 표지 다음에 합성 + 확신도 색', () => {
  const md = `# T\n\n## 1. 위험A\n\n결론: a.\n확신도: 확인됨 — 산술 확정.\n권고 조치:\n- 첫 조치 실행\n- 두번째\n\n## 확인 범위와 한계\n\n- 한계입니다.\n`;
  const entries = readZip(briefingToDocx(md));
  const doc = entries['word/document.xml'];
  assert.match(doc, /핵심 리스크 한눈에 보기/);
  assert.match(doc, /<w:t xml:space="preserve">R1<\/w:t>/);
  assert.match(doc, /첫 조치 실행/);
  // 리스크 절이 없으면 표가 없다
  const none = readZip(briefingToDocx('# T\n\n## 확인 범위와 한계\n\n- 한계.\n'));
  assert.ok(!/핵심 리스크 한눈에 보기/.test(none['word/document.xml']));
});

test('docx — 콜론 종결 짧은 문단은 소제목 승격, 라벨 문단은 제외', () => {
  const md = '# T\n\n## 뉴스 신호\n\n기업평판/브랜드 이미지:\n- 근거 예시: 민원 기사 감지.\n\n판별 테스트:\n';
  const doc = readZip(briefingToDocx(md))['word/document.xml'];
  // 소제목: 콜론 제거 + 22 사이즈 굵은 런
  assert.match(doc, /<w:sz w:val="22"\/><w:szCs w:val="22"\/><\/w:rPr><w:t xml:space="preserve">기업평판\/브랜드 이미지<\/w:t>/);
  // "판별 테스트:" 는 LABEL_SET 라벨이므로 소제목이 아니라 라벨 문단
  assert.match(doc, /판별 테스트\. /);
});

test('docx — briefingToDocx: 같은 입력이면 바이트 동일 (결정론)', () => {
  const a = briefingToDocx(SAMPLE_MD);
  const b = briefingToDocx(SAMPLE_MD);
  assert.ok(a.equals(b));
});

test('docx — briefingToDocx: h1/메타 없는 최소 마크다운도 렌더 (제목 폴백)', () => {
  const entries = readZip(briefingToDocx('그냥 문단 하나.', { confidential: false, date: '2026-07-10' }));
  assert.match(entries['word/document.xml'], /그냥 문단 하나\./);
  assert.match(entries['word/document.xml'], /작성일 2026-07-10/);
  assert.match(entries['docProps/core.xml'], /CEO 리스크 브리핑/);
  assert.ok(!/대외비/.test(entries['word/document.xml']));
  assert.ok(!/대외비/.test(entries['word/header1.xml']));
});

// ── CLI ──────────────────────────────────────────────────────
test('render-briefing-docx CLI — md → docx 생성 + 기본 out 경로', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-docx-'));
  try {
    const md = join(dir, 'briefing.md');
    writeFileSync(md, SAMPLE_MD);
    const r = runNode([CLI, md]);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /DOCX 생성/);
    const out = join(dir, 'briefing.docx');
    assert.ok(existsSync(out));
    const entries = readZip(readFileSync(out));
    assert.match(entries['word/document.xml'], /한빛커머스/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('render-briefing-docx CLI — 입력 없으면 사용법 (exit 1)', () => {
  const r = runNode([CLI]);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /사용법/);
});
