/**
 * zero-dependency .docx 라이터 — 리포트 산출물(제목/문단/표/목록)만 지원하는 최소 구현.
 *
 * .docx = OPC zip 패키지. 필수 3파트([Content_Types].xml, _rels/.rels,
 * word/document.xml)만 담고, zip 은 무압축(stored) + UTF-8 파일명 플래그로 쓴다
 * (corp-codes.mjs 의 zip 해제와 대칭 — 이 저장소는 압축 라이브러리를 쓰지 않는다).
 *
 * 블록 모델: { type: 'heading'|'para'|'table'|'list', ... } 배열을 받아 문서를 만든다.
 * 스타일 파트 없이 런 속성(굵기/크기)으로 제목을 표현한다 — Word/한컴/LibreOffice 호환.
 */

// ── zip (stored) ─────────────────────────────────────────────────────────────
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i += 1) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function dosDateTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

const ZIP_VERSION = 20;
const ZIP_FLAG_UTF8 = 0x0800;
const ZIP_METHOD_STORED = 0;

/** files: [{ name, data: Buffer|string }] → zip Buffer (무압축) */
export function zipStore(files, now = new Date()) {
  const { time, day } = dosDateTime(now);
  const locals = [];
  const centrals = [];
  let offset = 0;

  for (const file of files) {
    const name = Buffer.from(file.name, 'utf8');
    const data = Buffer.isBuffer(file.data) ? file.data : Buffer.from(file.data, 'utf8');
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(ZIP_VERSION, 4);
    local.writeUInt16LE(ZIP_FLAG_UTF8, 6);
    local.writeUInt16LE(ZIP_METHOD_STORED, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(day, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, name, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(ZIP_VERSION, 4);
    central.writeUInt16LE(ZIP_VERSION, 6);
    central.writeUInt16LE(ZIP_FLAG_UTF8, 8);
    central.writeUInt16LE(ZIP_METHOD_STORED, 10);
    central.writeUInt16LE(time, 12);
    central.writeUInt16LE(day, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(Buffer.concat([central, name]));

    offset += local.length + name.length + data.length;
  }

  const centralDir = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(centralDir.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralDir, eocd]);
}

// ── WordprocessingML ────────────────────────────────────────────────────────
export function xmlEscape(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

const FONT = 'Malgun Gothic';
const BODY_SIZE = 20;          // half-points — 10pt
const HEADING_SIZES = { 1: 32, 2: 26, 3: 22 };
const ACCENT = '1A466B';

function runXml(text, { bold = false, size = BODY_SIZE, color = '', italic = false } = {}) {
  const props = [
    `<w:rFonts w:ascii="${FONT}" w:eastAsia="${FONT}" w:hAnsi="${FONT}"/>`,
    bold ? '<w:b/>' : '',
    italic ? '<w:i/>' : '',
    color ? `<w:color w:val="${color}"/>` : '',
    `<w:sz w:val="${size}"/><w:szCs w:val="${size}"/>`,
  ].join('');
  return `<w:r><w:rPr>${props}</w:rPr><w:t xml:space="preserve">${xmlEscape(text)}</w:t></w:r>`;
}

function paragraphXml(runs, { spacingBefore = 0, spacingAfter = 120, align = '' } = {}) {
  const props = [
    `<w:spacing w:before="${spacingBefore}" w:after="${spacingAfter}"/>`,
    align ? `<w:jc w:val="${align}"/>` : '',
  ].join('');
  return `<w:p><w:pPr>${props}</w:pPr>${runs.join('')}</w:p>`;
}

const CELL_MARGIN = '<w:tcMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tcMar>';

function cellXml(text, { header = false, width = 0 } = {}) {
  const shade = header ? `<w:shd w:val="clear" w:color="auto" w:fill="${ACCENT}"/>` : '';
  const w = width ? `<w:tcW w:w="${width}" w:type="dxa"/>` : '<w:tcW w:w="0" w:type="auto"/>';
  const run = runXml(String(text ?? ''), { bold: header, size: header ? BODY_SIZE : 18, color: header ? 'FFFFFF' : '' });
  return `<w:tc><w:tcPr>${w}${shade}${CELL_MARGIN}</w:tcPr><w:p><w:pPr><w:spacing w:before="20" w:after="20"/></w:pPr>${run}</w:p></w:tc>`;
}

const PAGE_CONTENT_WIDTH = 9638; // A4 11906 - 좌우 여백 1134×2 (dxa)

function tableXml({ header = [], rows = [], widths = [] }) {
  const border = t => `<w:${t} w:val="single" w:sz="4" w:space="0" w:color="BFC8D0"/>`;
  const props = `<w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>${border('top')}${border('left')}${border('bottom')}${border('right')}${border('insideH')}${border('insideV')}</w:tblBorders></w:tblPr>`;
  // w:tblGrid 는 스키마 필수 — 없으면 Word 가 "문서 복구" 를 띄운다 (실측)
  const columnCount = Math.max(header.length, ...rows.map(r => r.length), 1);
  const defaultWidth = Math.floor(PAGE_CONTENT_WIDTH / columnCount);
  const grid = `<w:tblGrid>${Array.from({ length: columnCount }, (_, i) =>
    `<w:gridCol w:w="${widths[i] || defaultWidth}"/>`).join('')}</w:tblGrid>`;
  const headerRow = header.length
    ? `<w:tr><w:trPr><w:tblHeader/></w:trPr>${header.map((h, i) => cellXml(h, { header: true, width: widths[i] ?? 0 })).join('')}</w:tr>`
    : '';
  const bodyRows = rows.map(cells =>
    `<w:tr>${cells.map((c, i) => cellXml(c, { width: widths[i] ?? 0 })).join('')}</w:tr>`).join('');
  return `<w:tbl>${props}${grid}${headerRow}${bodyRows}</w:tbl><w:p><w:pPr><w:spacing w:after="120"/></w:pPr></w:p>`;
}

function blockXml(block) {
  switch (block.type) {
    case 'heading': {
      const level = Math.min(Math.max(block.level ?? 1, 1), 3);
      return paragraphXml(
        [runXml(block.text, { bold: true, size: HEADING_SIZES[level], color: level === 1 ? ACCENT : '' })],
        { spacingBefore: level === 1 ? 0 : 240, spacingAfter: 120 },
      );
    }
    case 'para':
      return paragraphXml([runXml(block.text, { bold: block.bold ?? false, italic: block.italic ?? false, color: block.color ?? '' })]);
    case 'list':
      return block.items
        .map(item => paragraphXml([runXml(`•  ${item}`)], { spacingAfter: 60 }))
        .join('');
    case 'table':
      return tableXml(block);
    default:
      throw new Error(`알 수 없는 블록 타입: ${block.type}`);
  }
}

const CONTENT_TYPES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>`;

const ROOT_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>`;

const DOCUMENT_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`;

const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="${FONT}" w:eastAsia="${FONT}" w:hAnsi="${FONT}"/><w:sz w:val="${BODY_SIZE}"/><w:szCs w:val="${BODY_SIZE}"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>`;

function corePropsXml(title, now) {
  const iso = now.toISOString();
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${xmlEscape(title)}</dc:title><dc:creator>kakaopay-invest-companion</dc:creator><cp:lastModifiedBy>kakaopay-invest-companion</cp:lastModifiedBy><dcterms:created xsi:type="dcterms:W3CDTF">${iso}</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">${iso}</dcterms:modified></cp:coreProperties>`;
}

const APP_PROPS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>kakaopay-invest-companion</Application></Properties>`;

/** blocks 배열 → .docx Buffer */
export function buildDocx({ blocks, title = '' }, now = new Date()) {
  const body = blocks.map(blockXml).join('');
  const document = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="720" w:footer="720"/></w:sectPr></w:body></w:document>`;

  const docTitle = title || (blocks.find(b => b.type === 'heading')?.text ?? 'report');
  return zipStore([
    { name: '[Content_Types].xml', data: CONTENT_TYPES },
    { name: '_rels/.rels', data: ROOT_RELS },
    { name: 'word/document.xml', data: document },
    { name: 'word/_rels/document.xml.rels', data: DOCUMENT_RELS },
    { name: 'word/styles.xml', data: STYLES },
    { name: 'docProps/core.xml', data: corePropsXml(docTitle, now) },
    { name: 'docProps/app.xml', data: APP_PROPS },
  ], now);
}
