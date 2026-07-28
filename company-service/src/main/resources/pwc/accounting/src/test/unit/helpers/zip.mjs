/**
 * 테스트용 단일 엔트리 zip 생성기 — corp-codes.mjs 의 unzipSingle 이 읽는
 * 최소 구조(local header + central directory + EOCD)만 만든다.
 */
import { deflateRawSync } from 'node:zlib';

const LOCAL_SIG = 0x04034b50;
const CENTRAL_SIG = 0x02014b50;
const EOCD_SIG = 0x06054b50;
export const ZIP_METHOD_DEFLATED = 8;
export const ZIP_METHOD_STORED = 0;

export function makeSingleEntryZip(name, content, { method = ZIP_METHOD_DEFLATED } = {}) {
  const nameBuf = Buffer.from(name, 'utf8');
  const raw = Buffer.from(content, 'utf8');
  const data = method === ZIP_METHOD_DEFLATED ? deflateRawSync(raw) : raw;

  const local = Buffer.alloc(30);
  local.writeUInt32LE(LOCAL_SIG, 0);
  local.writeUInt16LE(method, 8);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(raw.length, 22);
  local.writeUInt16LE(nameBuf.length, 26);
  local.writeUInt16LE(0, 28);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(CENTRAL_SIG, 0);
  central.writeUInt16LE(method, 10);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(raw.length, 24);
  central.writeUInt16LE(nameBuf.length, 28);
  central.writeUInt32LE(0, 42); // local header offset

  const cdOffset = 30 + nameBuf.length + data.length;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(EOCD_SIG, 0);
  eocd.writeUInt16LE(1, 8);
  eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(46 + nameBuf.length, 12);
  eocd.writeUInt32LE(cdOffset, 16);

  return Buffer.concat([local, nameBuf, data, central, nameBuf, eocd]);
}

/** zip 매직(local header 시그니처)으로 시작하지만 EOCD 가 없는 버퍼 */
export function makeZipWithoutEocd() {
  const buf = Buffer.alloc(64);
  buf.writeUInt32LE(LOCAL_SIG, 0);
  return buf;
}

export function makeCorpXml(companies) {
  const lists = companies.map(
    (c) => `<list><corp_code>${c.corpCode}</corp_code><corp_name>${c.name}</corp_name>` +
      `<stock_code>${c.stockCode ?? ' '}</stock_code><modify_date>${c.modifyDate ?? '20260101'}</modify_date></list>`,
  );
  return `<?xml version="1.0" encoding="UTF-8"?><result>${lists.join('')}</result>`;
}
