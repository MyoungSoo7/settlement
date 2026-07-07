/**
 * DART OpenAPI 클라이언트 (zero-dependency, Node 18+).
 *
 * 인증: 모든 요청에 crtfc_key 쿼리 파라미터 (env DART_API_KEY).
 * 키가 env 에 없으면 상위 디렉터리의 .env 에서 DART_API_KEY= 라인을 찾아 폴백한다
 * (해커톤 로컬 실행 편의 — 운영 배포 시엔 env 주입만 사용할 것).
 *
 * 응답 status 코드: "000" 정상, "013" 조회 데이터 없음, "020" 키 한도 초과,
 * "100" 잘못된 키, "800" 시스템 점검. 000/013 외에는 throw.
 */
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = 'https://opendart.fss.or.kr/api';

function findEnvKey() {
  if (process.env.DART_API_KEY) return process.env.DART_API_KEY;
  // 이 파일 위치에서 위로 올라가며 .env 탐색 (repo 루트의 .env 폴백)
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    const p = join(dir, '.env');
    if (existsSync(p)) {
      const m = readFileSync(p, 'utf8').match(/^DART_API_KEY=([^\r\n#]+)/m);
      if (m) return m[1].trim();
    }
    const parent = resolve(dir, '..');
    if (parent === dir) break;
    dir = parent;
  }
  return '';
}

export const API_KEY = findEnvKey();

export function requireKey() {
  if (!API_KEY) {
    throw new Error('DART_API_KEY 가 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://opendart.fss.or.kr)');
  }
}

async function getRaw(path, params = {}) {
  requireKey();
  const q = new URLSearchParams({ crtfc_key: API_KEY, ...params });
  const res = await fetch(`${BASE}/${path}?${q}`, { signal: AbortSignal.timeout(20_000) });
  if (!res.ok) throw new Error(`DART ${path} → HTTP ${res.status}`);
  return res;
}

export async function getJson(path, params = {}) {
  const res = await getRaw(path, params);
  const body = await res.json();
  if (body.status && body.status !== '000' && body.status !== '013') {
    throw new Error(`DART ${path} → status ${body.status}: ${body.message}`);
  }
  return body;
}

export async function getBinary(path, params = {}) {
  const res = await getRaw(path, params);
  const buf = Buffer.from(await res.arrayBuffer());
  // 오류 시 zip 대신 XML/JSON 오류 본문이 올 수 있다
  if (buf.length < 4 || buf.readUInt32LE(0) !== 0x04034b50) {
    const head = buf.toString('utf8', 0, Math.min(buf.length, 300));
    throw new Error(`DART ${path} → zip 이 아닌 응답: ${head}`);
  }
  return buf;
}

// ── 공개 API 래퍼 ────────────────────────────────────────────────────────────

/** 기업개황 — corp_code 8자리 */
export const company = corpCode => getJson('company.json', { corp_code: corpCode });

/**
 * 공시검색 — bgnDe/endDe: YYYYMMDD.
 * pblntfTy: A정기공시 B주요사항 C발행 D지분 E기타 F외부감사 G펀드 H자산유동화 I거래소 J공정위
 */
export const disclosures = ({ corpCode, bgnDe, endDe, pblntfTy, pageCount = 50, pageNo = 1 }) =>
  getJson('list.json', {
    ...(corpCode ? { corp_code: corpCode } : {}),
    bgn_de: bgnDe, end_de: endDe,
    ...(pblntfTy ? { pblntf_ty: pblntfTy } : {}),
    page_count: pageCount, page_no: pageNo,
  });

/**
 * 단일회사 주요계정 (요약) — reprtCode: 11011 사업보고서(연간) / 11012 반기 / 11013 1분기 / 11014 3분기
 */
export const financialSummary = ({ corpCode, year, reprtCode = '11011' }) =>
  getJson('fnlttSinglAcnt.json', { corp_code: corpCode, bsns_year: String(year), reprt_code: reprtCode });

/**
 * 단일회사 전체 재무제표 — fsDiv: CFS 연결 / OFS 별도
 */
export const financialFull = ({ corpCode, year, reprtCode = '11011', fsDiv = 'CFS' }) =>
  getJson('fnlttSinglAcntAll.json', {
    corp_code: corpCode, bsns_year: String(year), reprt_code: reprtCode, fs_div: fsDiv,
  });

/** 고유번호 전체 zip (CORPCODE.xml 1개 엔트리) — corp-codes.mjs 가 사용 */
export const corpCodeZip = () => getBinary('corpCode.xml');
