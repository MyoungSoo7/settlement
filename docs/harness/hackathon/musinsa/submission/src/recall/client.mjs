/**
 * 소비자24 리콜정보 OpenAPI 클라이언트 (공정거래위원회, zero-dependency) — 공식 리콜 DB 축.
 *
 * 뉴스 보도(프록시)와 달리 정부 공식 리콜 처분 기록이다. brand_risk_scan 의
 * "리스크 보도 없음"을 "공식 리콜 이력 없음"으로 한 단계 격상시킨다.
 * 응답은 XML 뿐이라 자체 파싱한다 (개발가이드 V2.5, 2024-10-16).
 *
 * env: DATA_GO_KR_API_KEY — 공공데이터포털 활용신청 후 제공기관(소비자24) 동기화까지
 * 시간이 걸릴 수 있다(코드 50). 그동안은 구분 가능한 안내 에러로 강등된다.
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const BASE = 'https://www.consumer.go.kr/openapi/recall/contents/index.do';
const REQUEST_TIMEOUT_MS = 20_000;

// 소비자24 는 자체 발급 인증번호(로그인 후 Open API 활용신청)를 우선 사용하고,
// 없으면 data.go.kr 공용 키로 폴백한다 (LINK 형이라 동기화 여부가 환경마다 다름 — 실측상 자체 키가 확실).
export const API_KEY = (findEnvKey('CONSUMER24_API_KEY', MODULE_DIR) || findEnvKey('DATA_GO_KR_API_KEY', MODULE_DIR)).replace(/[^\x21-\x7e]/g, '');

export function hasKey() {
  return Boolean(API_KEY);
}

/** 리콜정보 메뉴 ID (개발가이드 §3.1) — 패션·뷰티 관련만 노출하고 나머지는 참고용으로 유지. */
export const CATEGORIES = {
  '공산품': '0101',      // 의류·신발·잡화 포함
  '화장품': '0206',
  '해외리콜': '0501',    // 해외 브랜드 직구 리콜
  '식품': '0201',
  '생활화학제품': '0401',
  '의약품': '0204',
};

const ERROR_MESSAGES = {
  10: 'APPLICATION ERROR',
  20: 'DB ERROR',
  40: '잘못된 요청 파라미터',
  41: '일일 요청 한도 초과',
  50: '등록되지 않은 서비스키 — 소비자24(consumer.go.kr) 로그인 후 Open API 활용신청으로 발급받은 인증번호를 CONSUMER24_API_KEY 로 설정하세요 (data.go.kr LINK 신청만으로는 등록되지 않을 수 있음 — 실측). 그동안은 brand_risk_scan(뉴스 보도)으로 대체하세요.',
  51: '등록되지 않은 사용자',
  99: 'UNKNOWN ERROR',
};

function tag(block, name) {
  const m = block.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`));
  return m ? m[1].replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1').trim() : '';
}

function fmtYmd(value) {
  const s = String(value ?? '').trim();
  return /^\d{8}$/.test(s) ? `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6)}` : s;
}

export async function searchRecalls({ category = '화장품', productNm, bsnmNm, fromDate, toDate, page = 1, perPage = 20 }) {
  if (!hasKey()) {
    throw new Error('DATA_GO_KR_API_KEY 가 없습니다 — env 또는 상위 .env 에 설정하세요 (data.go.kr 에서 공정거래위원회_리콜정보 활용신청)');
  }
  const cntntsId = CATEGORIES[category] ?? (/^\d{4}$/.test(String(category)) ? String(category) : null);
  if (!cntntsId) {
    throw new Error(`category 는 ${Object.keys(CATEGORIES).join('/')} 또는 4자리 메뉴ID 여야 합니다 (받은 값: ${category})`);
  }
  const params = new URLSearchParams({
    serviceKey: API_KEY,
    pageNo: String(Math.max(1, Number(page) || 1)),
    cntPerPage: String(Math.max(1, Math.min(Number(perPage) || 20, 100))),
    cntntsId,
  });
  if (productNm) params.set('productNm', String(productNm).trim());
  if (bsnmNm) params.set('bsnmNm', String(bsnmNm).trim());
  if (fromDate) params.set('recallPublictBgnde', String(fromDate).replace(/-/g, ''));
  if (toDate) params.set('recallPublictEndde', String(toDate).replace(/-/g, ''));

  const res = await fetch(`${BASE}?${params}`, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
  const xml = await res.text();
  if (!res.ok) throw new Error(`소비자24 리콜 → HTTP ${res.status}`);

  const code = Number(tag(xml, 'code'));
  if (code === 30) {
    // NODATA — 검색 결과 없음은 에러가 아니라 "공식 리콜 이력 없음"이라는 유효한 결과
    return { category, cntntsId, query: { productNm, bsnmNm }, total: 0, items: [], note: '조건에 맞는 공식 리콜 이력이 없습니다 — "리콜 이력 없음을 점검함"도 유효한 결과입니다.' };
  }
  if (code !== 0) {
    const err = new Error(`소비자24 리콜 → 코드 ${code}: ${ERROR_MESSAGES[code] ?? tag(xml, 'codeMsg')}`);
    if (code === 50) err.keyPending = true;
    throw err;
  }

  const items = [...xml.matchAll(/<content>([\s\S]*?)<\/content>/g)].map(([, block]) => ({
    recallSn: tag(block, 'recallSn'),
    productName: tag(block, 'productNm'),
    maker: tag(block, 'makr'),
    businessName: tag(block, 'bsnmNm'),
    modelInfo: tag(block, 'modlNmInfo'),
    recallType: tag(block, 'recallSe'),
    defect: tag(block, 'shrtcomCn'),
    hazardGrade: tag(block, 'hrmflGrad'),
    consumerTips: tag(block, 'cnsmrGhvrTips'),
    publishedFrom: fmtYmd(tag(block, 'recallPublictBgnde')),
    recallCompany: tag(block, 'recallEntrpsInfo'),
    sourceOrg: tag(block, 'infoOriginInstt'),
    sourceUrl: tag(block, 'infoOriginUrl') || tag(block, 'infoCreatUrl'),
  }));

  return {
    category,
    cntntsId,
    query: { productNm: productNm ?? null, bsnmNm: bsnmNm ?? null },
    total: Number(tag(xml, 'allCnt')) || items.length,
    items,
    note: '공정거래위원회 소비자24 공식 리콜 DB (뉴스 프록시가 아닌 정부 처분 기록). 브랜드명은 상품명(productNm)과 사업자명(bsnmNm) 어느 쪽에 있을지 모르므로 양쪽 검색 병합을 권장.',
  };
}

/** 브랜드 키워드를 상품명·사업자명 양쪽으로 검색해 recallSn 기준 병합. */
export async function brandRecallCheck({ brand, categories = ['화장품', '공산품'], perPage = 10 }) {
  const name = String(brand ?? '').trim();
  if (!name) throw new Error('brand 는 비어 있을 수 없습니다');
  const cats = (Array.isArray(categories) ? categories : [categories]).filter((c) => CATEGORIES[c]);
  if (cats.length === 0) throw new Error(`categories 는 ${Object.keys(CATEGORIES).join('/')} 중에서 선택`);

  const seen = new Set();
  const items = [];
  const perQuery = [];
  for (const category of cats) {
    for (const field of ['productNm', 'bsnmNm']) {
      const result = await searchRecalls({ category, [field]: name, perPage });
      perQuery.push({ category, field, total: result.total });
      for (const item of result.items) {
        if (seen.has(item.recallSn)) continue;
        seen.add(item.recallSn);
        items.push({ ...item, matchedCategory: category });
      }
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
  }
  return {
    brand: name,
    categories: cats,
    perQuery,
    total: items.length,
    items,
    note: items.length === 0
      ? `공식 리콜 DB(소비자24)에서 "${name}" 관련 이력이 확인되지 않았습니다 — 뉴스 스캔(brand_risk_scan)과 교차하면 신뢰도가 올라갑니다.`
      : '공식 리콜 이력 발견 — 각 건의 sourceUrl 원문을 확인해 동명 오탐 여부를 검증하고 인용하세요.',
  };
}
