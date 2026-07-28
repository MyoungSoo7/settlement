/**
 * 국세청 사업자등록정보 진위확인/상태조회 클라이언트 (data.go.kr, Node 18+).
 *
 * 인증: serviceKey 쿼리 파라미터 (env DATA_GO_KR_API_KEY).
 * 키가 env 에 없으면 상위 디렉터리의 .env 에서 DATA_GO_KR_API_KEY= 라인을 찾아 폴백한다.
 *
 * 상태조회: 사업자등록번호만으로 계속/휴업/폐업/미등록 상태와 과세유형을 확인한다.
 * 진위확인: 사업자등록번호 + 개업일자 + 대표자명 등으로 국세청 등록정보 일치 여부를 확인한다.
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const BASE = 'https://api.odcloud.kr/api/nts-businessman/v1';
const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const REQUEST_TIMEOUT_MS = 20_000;
const CHECKSUM_WEIGHTS = [1, 3, 7, 1, 3, 7, 1, 3, 5];

export const API_KEY = findEnvKey('DATA_GO_KR_API_KEY', MODULE_DIR);

export function requireKey() {
  if (!API_KEY) {
    throw new Error('DATA_GO_KR_API_KEY 가 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://www.data.go.kr)');
  }
}

export function normalizeBusinessNumber(value) {
  return String(value ?? '').replace(/\D/g, '');
}

function checksumValid(normalized) {
  if (!/^\d{10}$/.test(normalized)) return false;
  const digits = [...normalized].map(Number);
  const weighted = CHECKSUM_WEIGHTS.reduce((sum, weight, idx) => sum + digits[idx] * weight, 0);
  const sum = weighted + Math.floor((digits[8] * 5) / 10);
  const check = (10 - (sum % 10)) % 10;
  return check === digits[9];
}

export function validateBusinessNumber(value) {
  const normalized = normalizeBusinessNumber(value);
  const formatValid = /^\d{10}$/.test(normalized);
  const checksum = checksumValid(normalized);
  return {
    input: String(value ?? ''),
    normalized,
    formatValid,
    checksumValid: checksum,
    valid: formatValid && checksum,
  };
}

// 재시도 정책: 5xx·네트워크 단절만 최대 3회, 백오프 1s→2s (NTS_RETRY_BASE_MS 로 조정 — 테스트는 1ms).
// 근거: 배치 실측(2026-07-15, 무작위 50개사)에서 NTS 503 이 21/50(42%) — 서버측 순간 장애·호출 누적
// 제한 패턴이라 수 초 백오프로 대부분 회수된다. 4xx(키 오류·형식 오류)는 재시도해도 같으므로 즉시 throw.
const RETRY_ATTEMPTS = 3;
const retryBaseMs = () => Number(process.env.NTS_RETRY_BASE_MS ?? 1_000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function postJson(path, payload) {
  requireKey();
  const params = new URLSearchParams({ serviceKey: API_KEY });
  let lastError;
  for (let attempt = 1; attempt <= RETRY_ATTEMPTS; attempt += 1) {
    let res;
    try {
      res = await fetch(`${BASE}/${path}?${params}`, {
        method: 'POST',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
    } catch (error) {
      lastError = new Error(`NTS business registry ${path} → ${error.message}`);
      if (attempt < RETRY_ATTEMPTS) { await sleep(retryBaseMs() * attempt); continue; }
      throw lastError;
    }
    const body = await res.json().catch(() => ({}));
    if (res.ok) return body;
    const message = body?.message ?? body?.errorMessage ?? JSON.stringify(body);
    lastError = new Error(`NTS business registry ${path} → HTTP ${res.status}: ${message}`);
    if (res.status >= 500 && attempt < RETRY_ATTEMPTS) { await sleep(retryBaseMs() * attempt); continue; }
    throw lastError;
  }
  throw lastError;
}

export async function businessStatusCheck({ businessNumbers }) {
  const numbers = (Array.isArray(businessNumbers) ? businessNumbers : [businessNumbers])
    .map(normalizeBusinessNumber)
    .filter(Boolean);
  if (numbers.length === 0) throw new Error('businessNumbers 는 최소 1개 이상이어야 합니다');
  if (numbers.length > 100) throw new Error('businessNumbers 는 한 번에 최대 100개까지 조회합니다');
  return postJson('status', { b_no: numbers });
}

function normalizeBusinessForAuth(business) {
  const out = {
    b_no: normalizeBusinessNumber(business?.b_no),
    start_dt: String(business?.start_dt ?? '').replace(/\D/g, ''),
    p_nm: String(business?.p_nm ?? '').trim(),
  };
  for (const key of ['p_nm2', 'b_nm', 'corp_no', 'b_sector', 'b_type']) {
    const value = String(business?.[key] ?? '').trim();
    if (value) out[key] = value;
  }
  return out;
}

export async function businessAuthCheck({ businesses }) {
  const rows = (Array.isArray(businesses) ? businesses : [businesses]).map(normalizeBusinessForAuth);
  if (rows.length === 0) throw new Error('businesses 는 최소 1개 이상이어야 합니다');
  if (rows.length > 100) throw new Error('businesses 는 한 번에 최대 100개까지 확인합니다');
  for (const row of rows) {
    if (!row.b_no || !row.start_dt || !row.p_nm) {
      throw new Error('businessAuthCheck 는 b_no, start_dt(YYYYMMDD), p_nm 이 필요합니다');
    }
  }
  return postJson('validate', { businesses: rows });
}

function firstStatusCode(result) {
  const first = result?.data?.[0] ?? result?.response?.body?.items?.item?.[0] ?? null;
  return first?.b_stt_cd ? String(first.b_stt_cd) : '';
}

function firstAuthCode(result) {
  const first = result?.data?.[0] ?? null;
  return first?.valid ? String(first.valid) : '';
}

export async function companyIdentityGate({
  companyName,
  businessNumber,
  representativeName,
  openingDate,
  stockCode,
  dartCorpCode,
  newsQueryName,
} = {}) {
  const name = String(companyName ?? '').trim();
  if (!name) throw new Error('companyName 은 필수입니다');
  const local = validateBusinessNumber(businessNumber);
  const identifiers = {
    stockCode: String(stockCode ?? '').trim() || null,
    dartCorpCode: String(dartCorpCode ?? '').trim() || null,
    newsQueryName: String(newsQueryName ?? '').trim() || name,
  };

  let businessStatus = null;
  let businessAuth = null;
  const warnings = [];

  if (!local.valid) {
    warnings.push('사업자등록번호 형식 또는 체크섬이 유효하지 않아 외부 조회를 생략했습니다.');
  } else if (!API_KEY) {
    warnings.push('DATA_GO_KR_API_KEY 가 없어 국세청 상태조회/진위확인을 생략했습니다.');
  } else {
    businessStatus = await businessStatusCheck({ businessNumbers: [local.normalized] });
    if (representativeName && openingDate) {
      businessAuth = await businessAuthCheck({
        businesses: [{
          b_no: local.normalized,
          start_dt: openingDate,
          p_nm: representativeName,
          b_nm: name,
        }],
      });
    } else {
      warnings.push('대표자명과 개업일자가 없어 국세청 진위확인은 생략하고 상태조회만 수행했습니다.');
    }
  }

  const statusCode = firstStatusCode(businessStatus);
  const authCode = firstAuthCode(businessAuth);
  const analysisAllowed = local.valid
    && (!statusCode || statusCode === '01')
    && (!authCode || authCode === '01');

  return {
    companyName: name,
    businessNumber: local,
    identifiers,
    businessStatus,
    businessAuth,
    analysisAllowed,
    warnings,
  };
}
