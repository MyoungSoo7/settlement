/**
 * 브리핑 Markdown → CEO 보고서 DOCX 렌더러 (zero-dependency).
 *
 * 외부 documents 플러그인에 맡겼던 Word 변환을 플러그인 안으로 가져온다 —
 * UTF-8 과 한글 폰트(맑은 고딕)를 코드로 보장해 "????" 인코딩 소실을 원천 차단하고,
 * 표지·요약 박스·라벨 강조·각주 면책·페이지 번호까지 CEO 제출용 서식을 결정론으로 만든다.
 *
 * DOCX = OPC zip. 여기서 zip 도 직접 쓴다(CRC32 + deflate, node:zlib 만 사용).
 */
import { deflateRawSync } from 'node:zlib';
import { readFileSync } from 'node:fs';

// ── 팔레트 (삼일PwC 계열 오렌지 액센트 + 네이비 본문 위계) ──
const ACCENT = 'D04A02';
const NAVY = '1B2A4A';
const GRAY = '595959';
const LIGHT_GRAY = 'A6A6A6';
const SUMMARY_FILL = 'FBF4EE';
const TABLE_HEAD_FILL = 'F2E8E0';
const GREEN = '1F7A33';
const AMBER = 'B45309';

// ── CRC32 (zip 필수 — Word 는 CRC 불일치 파일을 열지 않는다) ──
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i += 1) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// 고정 타임스탬프 — 같은 입력이면 바이트 단위로 같은 docx (테스트/디프 친화)
const DOS_DATE = ((2026 - 1980) << 9) | (7 << 5) | 1;
const DOS_TIME = 9 << 11;

/** entries: [{ name, data: Buffer|string }] → zip Buffer */
export function buildZip(entries) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, 'utf8');
    const raw = Buffer.isBuffer(entry.data) ? entry.data : Buffer.from(entry.data, 'utf8');
    const data = deflateRawSync(raw);
    const crc = crc32(raw);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);          // version needed
    local.writeUInt16LE(0x0800, 6);      // UTF-8 파일명 플래그
    local.writeUInt16LE(8, 8);           // deflate
    local.writeUInt16LE(DOS_TIME, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    chunks.push(local, nameBuf, data);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(8, 10);
    cd.writeUInt16LE(DOS_TIME, 12);
    cd.writeUInt16LE(DOS_DATE, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(data.length, 20);
    cd.writeUInt32LE(raw.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt32LE(offset, 42);
    central.push(cd, nameBuf);

    offset += 30 + nameBuf.length + data.length;
  }
  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, cdBuf, eocd]);
}

export function escapeXml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

// ── Markdown 파서 (브리핑에 실제로 쓰는 부분집합) ───────────
const LABEL_SET = new Set([
  '결론', '근거', '왜 문제인가', '확신도', '판별 테스트', '권고 조치',
  '추가 확인 절차', '근거 예시', 'CEO 확인 포인트', '연결 조언', '확인 포인트',
]);

/** **굵게** / [텍스트](링크) 인라인 → run 조각 배열 */
export function parseInline(text) {
  const runs = [];
  const re = /\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)\s]+)\)/g;
  let last = 0;
  let m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) runs.push({ text: text.slice(last, m.index) });
    if (m[1] !== undefined) runs.push({ text: m[1], bold: true });
    else runs.push({ text: m[2], link: m[3] });
    last = m.index + m[0].length;
  }
  if (last < text.length) runs.push({ text: text.slice(last) });
  return runs.filter((r) => r.text.length > 0);
}

/** Markdown → 블록 스트림 */
export function parseBlocks(markdown) {
  const lines = String(markdown ?? '').replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const trimmed = line.trimEnd();
    if (!trimmed.trim()) continue;
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(trimmed)) continue; // 수평선은 여백으로 대체

    const heading = /^(#{1,3})\s+(.*)$/.exec(trimmed);
    if (heading) {
      blocks.push({ type: `h${heading[1].length}`, text: heading[2].trim() });
      continue;
    }
    const bullet = /^(\s*)-\s+(.*)$/.exec(trimmed);
    if (bullet) {
      blocks.push({ type: 'bullet', level: bullet[1].length >= 2 ? 1 : 0, text: bullet[2].trim() });
      continue;
    }
    if (trimmed.startsWith('|') && lines[i + 1]?.trim().match(/^\|[\s:|-]+\|$/)) {
      const parseRow = (row) => row.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
      const header = parseRow(trimmed);
      const rows = [];
      i += 2;
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(parseRow(lines[i]));
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'table', header, rows });
      continue;
    }
    blocks.push({ type: 'para', text: trimmed.trim() });
  }
  return blocks;
}

// ── WordprocessingML 생성 ────────────────────────────────────
function runXml({ text, bold, italic, color, size, link, rId }) {
  const props = [];
  if (link) props.push('<w:rStyle w:val="Hyperlink"/>');
  if (bold) props.push('<w:b/>');
  if (italic) props.push('<w:i/>');
  if (color) props.push(`<w:color w:val="${color}"/>`);
  if (size) props.push(`<w:sz w:val="${size}"/><w:szCs w:val="${size}"/>`);
  const rPr = props.length ? `<w:rPr>${props.join('')}</w:rPr>` : '';
  const r = `<w:r>${rPr}<w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r>`;
  return link ? `<w:hyperlink r:id="${rId}">${r}</w:hyperlink>` : r;
}

function paragraph(runs, { style, jc, spacing, shd, ind, border, numId, level } = {}) {
  const props = [];
  if (style) props.push(`<w:pStyle w:val="${style}"/>`);
  if (numId !== undefined) props.push(`<w:numPr><w:ilvl w:val="${level ?? 0}"/><w:numId w:val="${numId}"/></w:numPr>`);
  if (border) props.push(border);
  if (shd) props.push(`<w:shd w:val="clear" w:color="auto" w:fill="${shd}"/>`);
  if (spacing) props.push(spacing);
  if (ind) props.push(ind);
  if (jc) props.push(`<w:jc w:val="${jc}"/>`);
  const pPr = props.length ? `<w:pPr>${props.join('')}</w:pPr>` : '';
  return `<w:p>${pPr}${runs.join('')}</w:p>`;
}

function pngSize(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 24) return null;
  if (buffer.readUInt32BE(0) !== 0x89504e47 || buffer.readUInt32BE(4) !== 0x0d0a1a0a) return null;
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

function imageParagraph({ rId, png, maxWidthIn = 6.5 }) {
  const size = pngSize(png) ?? { width: 1600, height: 1000 };
  const cx = Math.round(maxWidthIn * 914400);
  const cy = Math.round(cx * (size.height / size.width));
  return `<w:p><w:pPr><w:jc w:val="center"/><w:spacing w:before="120" w:after="240"/></w:pPr><w:r><w:drawing>
<wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" distT="0" distB="0" distL="0" distR="0">
<wp:extent cx="${cx}" cy="${cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="1" name="Executive Snapshot"/>
<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/></wp:cNvGraphicFramePr>
<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="1" name="executive-summary.png"/><pic:cNvPicPr/></pic:nvPicPr>
<pic:blipFill><a:blip r:embed="${rId}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>`;
}

const SUMMARY_BORDER = `<w:pBdr><w:left w:val="single" w:sz="24" w:space="8" w:color="${ACCENT}"/></w:pBdr>`;
const SUMMARY_SPACING = '<w:spacing w:before="60" w:after="120" w:line="276" w:lineRule="auto"/>';
const SUMMARY_IND = '<w:ind w:left="284" w:right="284"/>';

/** 확신도 값 색상 — 확인됨=녹색, 가설/확인 필요=주황 */
function confidenceColor(text) {
  if (/확인됨/.test(text)) return GREEN;
  if (/가설|확인 필요/.test(text)) return AMBER;
  return undefined;
}

/** 확신도 문장 → 요약표용 짧은 배지 텍스트 */
function confidenceBadge(text) {
  if (/확인됨/.test(text)) return '확인됨';
  if (/가설/.test(text)) return '가설';
  if (/확인 필요/.test(text)) return '확인 필요';
  return text.split(/[—.·,]/)[0].trim().slice(0, 12) || '-';
}

const NON_RISK_H2 = /^(요약|확인 범위와 한계|출처|참고|다음 단계|부록)/;

/**
 * "라벨: 값" / "**라벨**: 값" / "**라벨**"(단독 줄, 콜론 생략) 공통 분해 —
 * 에이전트가 라벨을 굵게 감싸거나 콜론을 생략하는 변형까지 흡수한다.
 * 라벨이 LABEL_SET 에 없으면 null.
 */
export function splitLabel(text) {
  const m = /^\*{0,2}([^*:]{2,24})\*{0,2}:\s*(.*)$/.exec(text ?? '');
  if (m && LABEL_SET.has(m[1].trim())) return { label: m[1].trim(), value: m[2].trim() };
  const bare = /^\*{0,2}([^*:]{2,24})\*{0,2}\s*$/.exec(text ?? '');
  if (bare && LABEL_SET.has(bare[1].trim())) return { label: bare[1].trim(), value: '' };
  return null;
}

/**
 * 리스크 섹션(##) 스캔 → Executive Summary 표 데이터.
 * 각 섹션에서 확신도 값과 최우선 권고 조치(권고 조치 라벨 뒤 첫 항목)를 뽑는다.
 */
export function extractRiskSummary(bodyBlocks) {
  const risks = [];
  let current = null;
  let collecting = null; // '권고 조치' 불릿 수집 상태
  for (const block of bodyBlocks) {
    if (block.type === 'h2' || block.type === 'h1') {
      if (current) risks.push(current);
      const cleanTitle = block.text.replace(/^(리스크\s*)?\d+[.)—-]?\s*[—-]?\s*/, '').trim() || block.text;
      current = NON_RISK_H2.test(cleanTitle)
        ? null
        : { title: cleanTitle, confidence: '', action: '' };
      collecting = null;
      continue;
    }
    if (!current) continue;
    const parsed = block.type === 'para' ? splitLabel(block.text) : null;
    if (parsed?.label === '확신도' && !current.confidence) {
      current.confidence = confidenceBadge(parsed.value);
      collecting = null;
    } else if (parsed?.label === '권고 조치') {
      if (parsed.value && !current.action) current.action = parsed.value;
      else collecting = '권고 조치';
    } else if (collecting === '권고 조치' && !current.action
      && (block.type === 'bullet' || (block.type === 'para' && /^\d+[.)]\s+/.test(block.text)))) {
      // "- 항목" 불릿과 "1. 항목" 번호 목록 둘 다 첫 항목을 최우선 조치로 수집
      current.action = block.text.replace(/^\d+[.)]\s+/, '').replace(/\*\*/g, '');
      collecting = null;
    } else if (parsed) {
      collecting = null;
    }
  }
  if (current) risks.push(current);
  return risks.filter((r) => r.confidence || r.action);
}

/**
 * briefing markdown → docx Buffer.
 * opts: { title, company, date, confidential=true }
 */
export function briefingToDocx(markdown, opts = {}) {
  const blocks = parseBlocks(markdown);
  const hyperlinks = [];
  const nextLinkId = () => `rIdLink${hyperlinks.length + 1}`;

  const toRuns = (text, base = {}) => parseInline(text).map((r) => {
    if (r.link) {
      const rId = nextLinkId();
      hyperlinks.push({ rId, target: r.link });
      return runXml({ ...base, ...r, rId });
    }
    return runXml({ ...base, ...r });
  });

  // 표지 정보: 첫 h1 + 직후 "라벨: 값" 문단들
  const h1 = blocks.find((b) => b.type === 'h1');
  const title = opts.title ?? h1?.text ?? 'CEO 리스크 브리핑';
  const meta = [];
  const bodyBlocks = [];
  let seenH1 = false;
  let collectingMeta = false;
  for (const block of blocks) {
    if (block.type === 'h1' && !seenH1) { seenH1 = true; collectingMeta = true; continue; }
    if (collectingMeta && block.type === 'para' && /^(\*{0,2})[^:*]{2,24}\1:\s/.test(block.text)) {
      const idx = block.text.indexOf(':');
      meta.push([
        block.text.slice(0, idx).replace(/\*\*/g, '').trim(),
        block.text.slice(idx + 1).trim(),
      ]);
      continue;
    }
    collectingMeta = false;
    bodyBlocks.push(block);
  }

  const body = [];

  // ── 표지 ──
  body.push(paragraph([runXml({ text: ' ' })], {
    shd: ACCENT, spacing: '<w:spacing w:before="0" w:after="480"/>',
  }));
  body.push(paragraph([runXml({ text: 'CEO 경영 리스크 브리핑', color: GRAY, size: 22 })], {
    spacing: '<w:spacing w:before="1200" w:after="120"/>',
  }));
  body.push(paragraph([runXml({ text: title, bold: true, color: NAVY, size: 52 })], {
    spacing: '<w:spacing w:after="360"/>',
  }));
  if (opts.confidential !== false) {
    body.push(paragraph([runXml({ text: '대외비 · Confidential', bold: true, color: ACCENT, size: 20 })], {
      spacing: `<w:spacing w:after="${opts.date ? 160 : 600}"/>`,
    }));
  }
  if (opts.date) {
    body.push(paragraph([runXml({ text: `작성일 ${opts.date}`, color: GRAY, size: 20 })], {
      spacing: '<w:spacing w:after="600"/>',
    }));
  }
  for (const [k, v] of meta) {
    body.push(paragraph([
      runXml({ text: `${k}  `, bold: true, color: NAVY, size: 20 }),
      ...toRuns(v, { color: GRAY, size: 20 }),
    ], { border: SUMMARY_BORDER, ind: '<w:ind w:left="284"/>', spacing: '<w:spacing w:after="80"/>' }));
  }
  body.push(paragraph([runXml({
    text: '본 보고서는 trusted-ceo-agent 진단 파이프라인(불변식 게이트 → 결정론 신호 파생 → 자동 채점)을 통과한 데이터에 기반해 작성되었으며, CEO 경영 판단 보조 자료로서 투자자문·투자권유가 아닙니다.',
    color: LIGHT_GRAY, size: 16,
  })], { spacing: '<w:spacing w:before="2400" w:after="0"/>' }));
  body.push('<w:p><w:r><w:br w:type="page"/></w:r></w:p>');

  let snapshotPng = null;
  if (opts.snapshotImagePath) {
    try {
      snapshotPng = readFileSync(opts.snapshotImagePath);
      body.push(paragraph([runXml({ text: 'Executive Snapshot', bold: true, color: NAVY })], { style: 'Heading1' }));
      body.push(imageParagraph({ rId: 'rIdSnapshot', png: snapshotPng }));
      body.push('<w:p><w:r><w:br w:type="page"/></w:r></w:p>');
    } catch {
      snapshotPng = null;
    }
  }

  // ── Executive Summary 표 (리스크 섹션이 있을 때만) ──
  const risks = extractRiskSummary(bodyBlocks);
  if (risks.length > 0) {
    body.push(paragraph([runXml({ text: '핵심 리스크 한눈에 보기', bold: true, color: NAVY })], { style: 'Heading1' }));
    const cell = (runs, { fill, width } = {}) => `<w:tc><w:tcPr>${
      width ? `<w:tcW w:w="${width}" w:type="dxa"/>` : '<w:tcW w:w="0" w:type="auto"/>'
    }${fill ? `<w:shd w:val="clear" w:color="auto" w:fill="${fill}"/>` : ''}<w:vAlign w:val="center"/></w:tcPr>${
      paragraph(runs, { spacing: '<w:spacing w:before="60" w:after="60"/>' })}</w:tc>`;
    const COLS = [700, 3300, 1100, 3970];
    const headRow = `<w:tr>${['구분', '리스크', '확신도', '최우선 조치'].map((h, i) =>
      cell([runXml({ text: h, bold: true, color: NAVY, size: 19 })], { fill: TABLE_HEAD_FILL, width: COLS[i] })).join('')}</w:tr>`;
    const bodyRows = risks.map((r, i) => `<w:tr>${[
      cell([runXml({ text: `R${i + 1}`, bold: true, color: ACCENT, size: 19 })], { width: COLS[0] }),
      cell(toRuns(r.title, { bold: true, size: 19 }), { width: COLS[1] }),
      cell([runXml({ text: r.confidence || '-', bold: true, color: confidenceColor(r.confidence) ?? GRAY, size: 19 })], { width: COLS[2] }),
      cell(toRuns(r.action || '-', { size: 19 }), { width: COLS[3] }),
    ].join('')}</w:tr>`).join('');
    body.push(`<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="${COLS.reduce((a, b) => a + b, 0)}" w:type="dxa"/><w:tblLayout w:type="fixed"/>
      <w:tblBorders><w:top w:val="single" w:sz="4" w:color="D9D9D9"/><w:left w:val="single" w:sz="4" w:color="D9D9D9"/><w:bottom w:val="single" w:sz="4" w:color="D9D9D9"/><w:right w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideH w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideV w:val="single" w:sz="4" w:color="D9D9D9"/></w:tblBorders>
    </w:tblPr>${headRow}${bodyRows}</w:tbl>`);
    body.push(paragraph([runXml({
      text: '표의 확신도는 본문 각 리스크 절의 확신도 표기를 그대로 요약한 것입니다. 상세 근거 수치와 판별 테스트는 본문을 참조하십시오.',
      color: LIGHT_GRAY, size: 17,
    })], { spacing: '<w:spacing w:before="80" w:after="240"/>' }));
  }

  // ── 본문 ──
  let inSummary = false;
  for (const block of bodyBlocks) {
    if (block.type === 'h1' || block.type === 'h2') {
      inSummary = /요약/.test(block.text);
      body.push(paragraph(toRuns(block.text, { bold: true, color: NAVY }), { style: 'Heading1' }));
      continue;
    }
    if (block.type === 'h3') {
      body.push(paragraph(toRuns(block.text, { bold: true, color: NAVY }), { style: 'Heading2' }));
      continue;
    }
    if (block.type === 'table') {
      body.push(tableXml(block, toRuns));
      continue;
    }
    if (block.type === 'bullet') {
      const runs = labelRuns(block.text, toRuns);
      body.push(paragraph(runs, {
        style: 'ListParagraph', numId: 1, level: block.level, jc: 'both',
        ...(inSummary ? { shd: SUMMARY_FILL } : {}),
      }));
      continue;
    }
    // 문단 — 라벨/요약/면책 구분
    if (/투자자문|투자권유가 아닙니다/.test(block.text) && bodyBlocks[bodyBlocks.length - 1] === block) {
      body.push(paragraph(toRuns(block.text, { italic: true, color: LIGHT_GRAY, size: 18 }), {
        jc: 'center', spacing: '<w:spacing w:before="360" w:after="0"/>',
      }));
      continue;
    }
    // "기업평판/브랜드 이미지:" 같은 콜론 종결 짧은 문단 = 소제목 (라벨 문단은 제외)
    if (!splitLabel(block.text) && /^[^.]{2,28}:$/.test(block.text)) {
      body.push(paragraph([runXml({ text: block.text.slice(0, -1), bold: true, color: NAVY, size: 22 })], {
        spacing: '<w:spacing w:before="240" w:after="80"/>',
      }));
      continue;
    }
    const runs = labelRuns(block.text, toRuns);
    body.push(paragraph(runs, inSummary
      ? { shd: SUMMARY_FILL, border: SUMMARY_BORDER, spacing: SUMMARY_SPACING, ind: SUMMARY_IND, jc: 'both' }
      : { jc: 'both' }));
  }

  const sectPr = `<w:sectPr>
    <w:headerReference w:type="default" r:id="rIdHeader"/>
    <w:footerReference w:type="default" r:id="rIdFooter"/>
    <w:pgSz w:w="11906" w:h="16838"/>
    <w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/>
    <w:titlePg/>
  </w:sectPr>`;

  const documentXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<w:body>${body.join('\n')}${sectPr}</w:body>
</w:document>`;

  return buildZip(packageEntries({ documentXml, hyperlinks, title, opts, snapshotPng }));
}

/** "결론: ..." / "**결론**: ..." 라벨 문단 → 라벨 굵게 + 확신도는 배지 단어만 착색 */
function labelRuns(text, toRuns) {
  const parsed = splitLabel(text);
  if (parsed) {
    const runs = [runXml({ text: `${parsed.label}. `, bold: true, color: NAVY })];
    if (parsed.label === '확신도') {
      const badge = /^(확인됨|가설|확인 필요)/.exec(parsed.value);
      if (badge) {
        runs.push(runXml({ text: badge[1], bold: true, color: confidenceColor(badge[1]) }));
        runs.push(...toRuns(parsed.value.slice(badge[1].length)));
        return runs;
      }
    }
    runs.push(...toRuns(parsed.value));
    return runs;
  }
  return toRuns(text);
}

function tableXml(block, toRuns) {
  const contentWidth = 9070;
  const columnCount = Math.max(1, block.header.length);
  const widths = columnCount === 3
    ? [2700, 1200, 5170]
    : Array.from({ length: columnCount }, (_, index) => {
        const base = Math.floor(contentWidth / columnCount);
        return index === columnCount - 1 ? contentWidth - base * (columnCount - 1) : base;
      });
  const grid = `<w:tblGrid>${widths.map((width) => `<w:gridCol w:w="${width}"/>`).join('')}</w:tblGrid>`;
  const cell = (text, isHead, index) => `<w:tc><w:tcPr><w:tcW w:w="${widths[index] ?? widths.at(-1)}" w:type="dxa"/>${
    isHead ? `<w:shd w:val="clear" w:color="auto" w:fill="${TABLE_HEAD_FILL}"/>` : ''
  }</w:tcPr>${paragraph(toRuns(text, isHead ? { bold: true, color: NAVY, size: 19 } : { size: 19 }),
    { spacing: '<w:spacing w:before="40" w:after="40"/>' })}</w:tc>`;
  const row = (cells, isHead) => `<w:tr>${cells.map((c, index) => cell(c, isHead, index)).join('')}</w:tr>`;
  return `<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="${contentWidth}" w:type="dxa"/><w:tblLayout w:type="fixed"/>
    <w:tblBorders><w:top w:val="single" w:sz="4" w:color="D9D9D9"/><w:left w:val="single" w:sz="4" w:color="D9D9D9"/><w:bottom w:val="single" w:sz="4" w:color="D9D9D9"/><w:right w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideH w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideV w:val="single" w:sz="4" w:color="D9D9D9"/></w:tblBorders>
  </w:tblPr>${grid}${row(block.header, true)}${block.rows.map((r) => row(r, false)).join('')}</w:tbl>`;
}

function packageEntries({ documentXml, hyperlinks, title, opts, snapshotPng }) {
  const stylesXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr>
  <w:rFonts w:ascii="Malgun Gothic" w:eastAsia="Malgun Gothic" w:hAnsi="Malgun Gothic" w:cs="Malgun Gothic"/>
  <w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="ko-KR" w:eastAsia="ko-KR"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:keepNext/><w:spacing w:before="400" w:after="160"/>
  <w:pBdr><w:bottom w:val="single" w:sz="8" w:space="4" w:color="${ACCENT}"/></w:pBdr><w:outlineLvl w:val="0"/></w:pPr>
  <w:rPr><w:b/><w:color w:val="${NAVY}"/><w:sz w:val="27"/><w:szCs w:val="27"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:keepNext/><w:spacing w:before="280" w:after="120"/><w:outlineLvl w:val="1"/></w:pPr>
  <w:rPr><w:b/><w:color w:val="${NAVY}"/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:spacing w:after="80"/><w:ind w:left="425"/></w:pPr></w:style>
<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/>
  <w:rPr><w:color w:val="1155CC"/><w:u w:val="single"/></w:rPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style>
</w:styles>`;

  const numberingXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0">
  <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/>
    <w:pPr><w:ind w:left="425" w:hanging="212"/></w:pPr>
    <w:rPr><w:color w:val="${ACCENT}"/></w:rPr></w:lvl>
  <w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/><w:lvlText w:val="–"/>
    <w:pPr><w:ind w:left="850" w:hanging="212"/></w:pPr>
    <w:rPr><w:color w:val="${GRAY}"/></w:rPr></w:lvl>
</w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>`;

  const headerXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="D9D9D9"/></w:pBdr>
  <w:tabs><w:tab w:val="right" w:pos="9070"/></w:tabs><w:spacing w:after="0"/></w:pPr>
<w:r><w:rPr><w:color w:val="${LIGHT_GRAY}"/><w:sz w:val="16"/></w:rPr><w:t xml:space="preserve">CEO Risk Briefing</w:t></w:r>
<w:r><w:rPr><w:color w:val="${LIGHT_GRAY}"/><w:sz w:val="16"/></w:rPr><w:tab/></w:r>
<w:r><w:rPr><w:b/><w:color w:val="${ACCENT}"/><w:sz w:val="16"/></w:rPr><w:t>${opts.confidential !== false ? 'Confidential' : ''}</w:t></w:r>
</w:p></w:hdr>`;

  const footerXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:pBdr><w:top w:val="single" w:sz="4" w:space="4" w:color="D9D9D9"/></w:pBdr>
  <w:tabs><w:tab w:val="right" w:pos="9070"/></w:tabs><w:spacing w:after="0"/></w:pPr>
<w:r><w:rPr><w:color w:val="${LIGHT_GRAY}"/><w:sz w:val="16"/></w:rPr><w:t xml:space="preserve">Management risk briefing | Not investment advice</w:t></w:r>
<w:r><w:rPr><w:color w:val="${LIGHT_GRAY}"/><w:sz w:val="16"/></w:rPr><w:tab/></w:r>
<w:r><w:fldChar w:fldCharType="begin"/></w:r>
<w:r><w:rPr><w:color w:val="${LIGHT_GRAY}"/><w:sz w:val="16"/></w:rPr><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
<w:r><w:fldChar w:fldCharType="end"/></w:r>
</w:p></w:ftr>`;

  const linkRels = hyperlinks.map((h) =>
    `<Relationship Id="${h.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${escapeXml(h.target)}" TargetMode="External"/>`).join('');
  const documentRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
${snapshotPng ? '<Relationship Id="rIdSnapshot" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/executive-summary.png"/>' : ''}
${linkRels}
</Relationships>`;

  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
${snapshotPng ? '<Default Extension="png" ContentType="image/png"/>' : ''}
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>`;

  const rootRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>`;

  const created = opts.date ?? '2026-07-01';
  const coreXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>${escapeXml(title)}</dc:title>
<dc:creator>Trusted CEO Agent</dc:creator>
<cp:lastModifiedBy>Trusted CEO Agent</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">${escapeXml(created)}T00:00:00Z</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">${escapeXml(created)}T00:00:00Z</dcterms:modified>
</cp:coreProperties>`;

  const appXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
<Application>trusted-ceo-agent</Application>
</Properties>`;

  return [
    { name: '[Content_Types].xml', data: contentTypes },
    { name: '_rels/.rels', data: rootRels },
    { name: 'word/document.xml', data: documentXml },
    { name: 'word/styles.xml', data: stylesXml },
    { name: 'word/numbering.xml', data: numberingXml },
    { name: 'word/header1.xml', data: headerXml },
    { name: 'word/footer1.xml', data: footerXml },
    { name: 'word/_rels/document.xml.rels', data: documentRels },
    { name: 'docProps/core.xml', data: coreXml },
    { name: 'docProps/app.xml', data: appXml },
    ...(snapshotPng ? [{ name: 'word/media/executive-summary.png', data: snapshotPng }] : []),
  ];
}
