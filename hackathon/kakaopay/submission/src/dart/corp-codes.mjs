/**
 * DART 고유번호(corp_code) 캐시 — corpCode.xml(zip) 을 내려받아 JSON 으로 캐시하고
 * 기업명/종목코드로 검색한다. DART 의 모든 조회는 corp_code(8자리)를 요구하므로
 * 이 모듈이 모든 도구의 진입점이 된다.
 *
 * 캐시: src/data/cache/corp-codes.json (기본 상장사만 — stock_code 있는 ~4천 건).
 * zero-dependency zip 해제: EOCD → central directory → local header → inflateRawSync.
 */
import { inflateRawSync } from 'node:zlib';
import { readFileSync, writeFileSync, existsSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { corpCodeZip } from './client.mjs';

// CORP_CODES_CACHE env 는 테스트 픽스처 주입용 (네트워크 0 단위테스트 — test/unit 참조)
const CACHE = process.env.CORP_CODES_CACHE
  ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'cache', 'corp-codes.json');
const CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7일 — 고유번호는 자주 안 바뀐다
const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
const LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
const ZIP_MIN_EOCD_SIZE = 22;
const ZIP_MAX_COMMENT_SIZE = 65_536;
const EOCD_CENTRAL_DIRECTORY_OFFSET = 16;
const CENTRAL_DIRECTORY_METHOD_OFFSET = 10;
const CENTRAL_DIRECTORY_COMPRESSED_SIZE_OFFSET = 20;
const CENTRAL_DIRECTORY_LOCAL_HEADER_OFFSET = 42;
const LOCAL_HEADER_NAME_LENGTH_OFFSET = 26;
const LOCAL_HEADER_EXTRA_LENGTH_OFFSET = 28;
const LOCAL_HEADER_DATA_OFFSET = 30;
const ZIP_METHOD_STORED = 0;
const ZIP_METHOD_DEFLATED = 8;

// ── zip 단일 엔트리 해제 (CORPCODE.xml 전용 최소 구현) ───────────────────────
function unzipSingle(buf) {
  // EOCD(PK\x05\x06) 를 꼬리에서 탐색
  let eocd = -1;
  const searchStart = buf.length - ZIP_MIN_EOCD_SIZE;
  const searchEnd = Math.max(0, searchStart - ZIP_MAX_COMMENT_SIZE);
  for (let i = searchStart; i >= searchEnd; i -= 1) {
    if (buf.readUInt32LE(i) === EOCD_SIGNATURE) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('zip EOCD 를 찾지 못함');
  const cdOffset = buf.readUInt32LE(eocd + EOCD_CENTRAL_DIRECTORY_OFFSET);
  if (buf.readUInt32LE(cdOffset) !== CENTRAL_DIRECTORY_SIGNATURE) throw new Error('central directory 시그니처 불일치');
  const method = buf.readUInt16LE(cdOffset + CENTRAL_DIRECTORY_METHOD_OFFSET);
  const compSize = buf.readUInt32LE(cdOffset + CENTRAL_DIRECTORY_COMPRESSED_SIZE_OFFSET);
  const localOffset = buf.readUInt32LE(cdOffset + CENTRAL_DIRECTORY_LOCAL_HEADER_OFFSET);
  if (buf.readUInt32LE(localOffset) !== LOCAL_FILE_HEADER_SIGNATURE) throw new Error('local header 시그니처 불일치');
  const nameLen = buf.readUInt16LE(localOffset + LOCAL_HEADER_NAME_LENGTH_OFFSET);
  const extraLen = buf.readUInt16LE(localOffset + LOCAL_HEADER_EXTRA_LENGTH_OFFSET);
  const dataStart = localOffset + LOCAL_HEADER_DATA_OFFSET + nameLen + extraLen;
  const comp = buf.subarray(dataStart, dataStart + compSize);
  if (method === ZIP_METHOD_DEFLATED) return inflateRawSync(comp);
  if (method === ZIP_METHOD_STORED) return Buffer.from(comp);
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

function rankName(company, keyword) {
  if (company.name === keyword) return 0;
  if (company.name.startsWith(keyword)) return 1;
  return 2;
}

export async function loadCorpCodes({ refresh = false, listedOnly = true } = {}) {
  if (!refresh && existsSync(CACHE)) {
    try {
      const cached = JSON.parse(readFileSync(CACHE, 'utf8'));
      if (Date.now() - new Date(cached.fetchedAt).getTime() < CACHE_TTL_MS) return cached;
    } catch {
      // 손상된 캐시(쓰기 도중 중단 등)는 miss 로 취급하고 아래에서 재다운로드한다 —
      // 여기서 throw 하면 수동 삭제 전까지 모든 DART 도구가 영구 실패한다.
    }
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
  // 원자적 쓰기 — temp 에 다 쓴 뒤 rename. 프로세스가 도중에 죽어도 부분 JSON 이 남지 않는다.
  const tmp = `${CACHE}.tmp-${process.pid}`;
  writeFileSync(tmp, JSON.stringify(data));
  try {
    renameSync(tmp, CACHE);
  } catch (error) {
    rmSync(tmp, { force: true });   // rename 실패 시 고아 temp 정리 (데이터는 메모리로 반환되므로 동작 지속)
    throw error;
  }
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
    hits.sort((a, b) => rankName(a, kw) - rankName(b, kw) || a.name.length - b.name.length);
  }
  return { cacheDate: fetchedAt, matches: hits.slice(0, limit) };
}
