/**
 * DART 고유번호(corp_code) 캐시 — corpCode.xml(zip) 을 내려받아 JSON 으로 캐시하고
 * 기업명/종목코드로 검색한다. DART 의 모든 조회는 corp_code(8자리)를 요구하므로
 * 이 모듈이 모든 도구의 진입점이 된다.
 *
 * 캐시: src/data/cache/corp-codes.json (기본 상장사만 — stock_code 있는 ~4천 건).
 * zero-dependency zip 해제: EOCD → central directory → local header → inflateRawSync.
 */
import { inflateRawSync } from 'node:zlib';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { corpCodeZip } from './client.mjs';

const CACHE = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'cache', 'corp-codes.json');
const CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7일 — 고유번호는 자주 안 바뀐다

// ── zip 단일 엔트리 해제 (CORPCODE.xml 전용 최소 구현) ───────────────────────
function unzipSingle(buf) {
  // EOCD(PK\x05\x06) 를 꼬리에서 탐색
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65536); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip EOCD 를 찾지 못함');
  const cdOffset = buf.readUInt32LE(eocd + 16);
  if (buf.readUInt32LE(cdOffset) !== 0x02014b50) throw new Error('central directory 시그니처 불일치');
  const method = buf.readUInt16LE(cdOffset + 10);
  const compSize = buf.readUInt32LE(cdOffset + 20);
  const localOffset = buf.readUInt32LE(cdOffset + 42);
  if (buf.readUInt32LE(localOffset) !== 0x04034b50) throw new Error('local header 시그니처 불일치');
  const nameLen = buf.readUInt16LE(localOffset + 26);
  const extraLen = buf.readUInt16LE(localOffset + 28);
  const dataStart = localOffset + 30 + nameLen + extraLen;
  const comp = buf.subarray(dataStart, dataStart + compSize);
  if (method === 8) return inflateRawSync(comp);
  if (method === 0) return Buffer.from(comp);
  throw new Error(`지원하지 않는 zip 압축 방식: ${method}`);
}

// ── CORPCODE.xml 파싱 (단순 구조라 정규식으로 충분) ─────────────────────────
function parseCorpXml(xml) {
  const out = [];
  const re = /<list>([\s\S]*?)<\/list>/g;
  const field = (block, tag) => {
    const m = block.match(new RegExp(`<${tag}>([^<]*)</${tag}>`));
    return m ? m[1].trim() : '';
  };
  let m;
  while ((m = re.exec(xml)) !== null) {
    const b = m[1];
    out.push({
      corpCode: field(b, 'corp_code'),
      name: field(b, 'corp_name'),
      stockCode: field(b, 'stock_code'),
      modifyDate: field(b, 'modify_date'),
    });
  }
  return out;
}

export async function loadCorpCodes({ refresh = false, listedOnly = true } = {}) {
  if (!refresh && existsSync(CACHE)) {
    const cached = JSON.parse(readFileSync(CACHE, 'utf8'));
    if (Date.now() - new Date(cached.fetchedAt).getTime() < CACHE_TTL_MS) return cached;
  }
  const zip = await corpCodeZip();
  const xml = unzipSingle(zip).toString('utf8');
  const all = parseCorpXml(xml);
  const listed = all.filter(c => c.stockCode);
  const data = {
    fetchedAt: new Date().toISOString(),
    totalCount: all.length,
    listedCount: listed.length,
    companies: listedOnly ? listed : all,
  };
  mkdirSync(dirname(CACHE), { recursive: true });
  writeFileSync(CACHE, JSON.stringify(data));
  return data;
}

/** 기업명 일부 또는 종목코드(6자리)/고유번호(8자리)로 검색 */
export async function searchCorp(keyword, { limit = 10 } = {}) {
  const { companies, fetchedAt } = await loadCorpCodes();
  const kw = String(keyword).trim();
  let hits;
  if (/^\d{8}$/.test(kw)) hits = companies.filter(c => c.corpCode === kw);
  else if (/^\d{6}$/.test(kw)) hits = companies.filter(c => c.stockCode === kw);
  else {
    const lower = kw.toLowerCase();
    hits = companies.filter(c => c.name.toLowerCase().includes(lower));
    // 정확 일치 → 접두 일치 → 짧은 이름 순으로 정렬 (삼성전자 < 삼성전자서비스)
    hits.sort((a, b) =>
      (a.name === kw ? -1 : b.name === kw ? 1 : 0)
      || (a.name.startsWith(kw) ? -1 : 0) - (b.name.startsWith(kw) ? -1 : 0)
      || a.name.length - b.name.length);
  }
  return { cacheDate: fetchedAt, matches: hits.slice(0, limit) };
}
