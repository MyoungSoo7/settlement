/**
 * 기상청 단기예보 OpenAPI 클라이언트 (data.go.kr, zero-dependency) — 날씨×코디 축.
 *
 * "이번 주 뭐 입지"에 실제 기온·강수 예보로 답하기 위한 축. 실황(getUltraSrtNcst)과
 * 단기예보(getVilageFcst, ~3일)를 코디 판단에 필요한 형태(일별 최저/최고·강수·바람)로
 * 정규화한다. env: DATA_GO_KR_API_KEY (없으면 상위 .env 폴백).
 *
 * 키 방어: data.go.kr 키에 비ASCII 문자가 붙어 들어오는 사고가 실제로 있었다
 * (한글 오타 1자로 전 API 401) — 로딩 시점에 제거한다.
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey } from '../common/env.mjs';

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const BASE = 'https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0';
const REQUEST_TIMEOUT_MS = 20_000;

export const API_KEY = findEnvKey('DATA_GO_KR_API_KEY', MODULE_DIR).replace(/[^\x21-\x7e]/g, '');

export function hasKey() {
  return Boolean(API_KEY);
}

/** 기상청 격자 좌표 — 주요 도시. 목록에 없으면 nx/ny 직접 지정 가능. */
export const REGIONS = {
  '서울': [60, 127], '부산': [98, 76], '대구': [89, 90], '인천': [55, 124],
  '광주': [58, 74], '대전': [67, 100], '울산': [102, 84], '세종': [66, 103],
  '수원': [60, 121], '성남': [63, 124], '고양': [57, 128], '용인': [64, 119],
  '창원': [90, 77], '청주': [69, 106], '전주': [63, 89], '천안': [63, 110],
  '춘천': [73, 134], '강릉': [92, 131], '포항': [102, 94], '제주': [52, 38],
};

export function resolveGrid({ region, nx, ny }) {
  if (Number.isInteger(Number(nx)) && Number.isInteger(Number(ny)) && nx && ny) {
    return { nx: Number(nx), ny: Number(ny), label: `격자(${nx},${ny})` };
  }
  const name = String(region ?? '서울').trim();
  const hit = Object.keys(REGIONS).find((r) => name.startsWith(r) || r.startsWith(name));
  if (!hit) {
    throw new Error(`지원 지역 목록에 없습니다: "${name}" — 지원: ${Object.keys(REGIONS).join(', ')} (또는 nx/ny 격자 직접 지정)`);
  }
  return { nx: REGIONS[hit][0], ny: REGIONS[hit][1], label: hit };
}

const PTY_LABEL = { 0: '없음', 1: '비', 2: '비/눈', 3: '눈', 4: '소나기', 5: '빗방울', 6: '빗방울눈날림', 7: '눈날림' };
const SKY_LABEL = { 1: '맑음', 3: '구름많음', 4: '흐림' };

/** 기온 밴드 → 옷차림 가이드 (통용 기준) — 에이전트가 근거로 인용할 수 있게 결과에 동봉. */
export const DRESS_GUIDE = [
  { minTemp: 28, wear: '민소매·반팔, 린넨 소재, 반바지' },
  { minTemp: 23, wear: '반팔, 얇은 셔츠, 면바지' },
  { minTemp: 20, wear: '긴팔티, 얇은 가디건, 데님' },
  { minTemp: 17, wear: '니트·맨투맨·후드, 청자켓' },
  { minTemp: 12, wear: '자켓, 트렌치코트, 레이어드' },
  { minTemp: 9, wear: '코트, 얇은 니트 레이어드' },
  { minTemp: 5, wear: '울 코트, 히트텍, 목도리' },
  { minTemp: -99, wear: '패딩, 두꺼운 코트, 기모, 장갑' },
];

function dressFor(temp) {
  return DRESS_GUIDE.find((g) => temp >= g.minTemp)?.wear ?? DRESS_GUIDE.at(-1).wear;
}

function fmtDate(d) {
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

async function callApi(operation, params) {
  if (!hasKey()) {
    throw new Error('DATA_GO_KR_API_KEY 가 없습니다 — env 또는 상위 .env 에 설정하세요 (발급: https://www.data.go.kr, 기상청_단기예보 조회서비스 활용신청)');
  }
  const qs = new URLSearchParams({ serviceKey: API_KEY, dataType: 'JSON', pageNo: '1', ...params });
  const res = await fetch(`${BASE}/${operation}?${qs}`, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
  const text = await res.text();
  if (!res.ok) throw new Error(`기상청 ${operation} → HTTP ${res.status} ${text.slice(0, 120)}`);
  let body;
  try { body = JSON.parse(text); } catch { throw new Error(`기상청 ${operation} → JSON 아님 (게이트웨이 오류일 수 있음): ${text.slice(0, 120)}`); }
  const header = body?.response?.header;
  if (header?.resultCode !== '00') throw new Error(`기상청 ${operation} → ${header?.resultCode} ${header?.resultMsg}`);
  return body.response.body?.items?.item ?? [];
}

/** 초단기실황 — 현재 기온/강수/습도/바람. */
export async function currentWeather({ region, nx, ny, now = new Date() }) {
  const grid = resolveGrid({ region, nx, ny });
  const base = new Date(now.getTime() - 60 * 60 * 1000); // 정시 관측이 ~40분 후 제공 — 1시간 전 기준이 안전
  const items = await callApi('getUltraSrtNcst', {
    numOfRows: '10', base_date: fmtDate(base),
    base_time: `${String(base.getHours()).padStart(2, '0')}00`,
    nx: String(grid.nx), ny: String(grid.ny),
  });
  const val = (cat) => items.find((i) => i.category === cat)?.obsrValue;
  const temp = Number(val('T1H'));
  return {
    region: grid.label,
    observedAt: items[0] ? `${items[0].baseDate} ${items[0].baseTime}` : null,
    tempC: Number.isFinite(temp) ? temp : null,
    precipitation: PTY_LABEL[Number(val('PTY'))] ?? '없음',
    rain1hMm: Number(val('RN1')) || 0,
    humidityPct: Number(val('REH')) || null,
    windMs: Number(val('WSD')) || null,
    dressSuggestion: Number.isFinite(temp) ? dressFor(temp) : null,
    note: '기상청 초단기실황 (관측값). 옷차림 제안은 통용 기온 밴드 기준이며 개인 체감에 따라 조정하세요.',
  };
}

/** 단기예보(~3일)를 일별 코디 브리핑으로 집계 — 최저/최고기온, 최대 강수확률, 강수형태, 하늘. */
export async function outfitBrief({ region, nx, ny, days = 3, now = new Date() }) {
  const grid = resolveGrid({ region, nx, ny });
  const span = Math.max(1, Math.min(Number(days) || 3, 3));
  // 05시 발표분이 오늘 TMN/TMX 를 모두 포함 — 05:45 이전이면 전날 23시 발표분 사용
  let base = new Date(now);
  let baseTime = '0500';
  if (base.getHours() < 6) { base = new Date(base.getTime() - 24 * 60 * 60 * 1000); baseTime = '2300'; }
  const items = await callApi('getVilageFcst', {
    numOfRows: '900', base_date: fmtDate(base), base_time: baseTime,
    nx: String(grid.nx), ny: String(grid.ny),
  });

  const byDate = new Map();
  for (const it of items) {
    if (!byDate.has(it.fcstDate)) byDate.set(it.fcstDate, []);
    byDate.get(it.fcstDate).push(it);
  }

  const dailies = [...byDate.keys()].sort().slice(0, span).map((date) => {
    const day = byDate.get(date);
    const nums = (cat) => day.filter((i) => i.category === cat).map((i) => Number(i.fcstValue)).filter(Number.isFinite);
    const temps = nums('TMP');
    const tmn = nums('TMN')[0] ?? (temps.length ? Math.min(...temps) : null);
    const tmx = nums('TMX')[0] ?? (temps.length ? Math.max(...temps) : null);
    const popMax = nums('POP').length ? Math.max(...nums('POP')) : null;
    const ptyCodes = [...new Set(nums('PTY').filter((v) => v > 0))];
    const noonSky = day.find((i) => i.category === 'SKY' && i.fcstTime === '1200') ?? day.find((i) => i.category === 'SKY');
    return {
      date,
      minTempC: tmn,
      maxTempC: tmx,
      rainChanceMaxPct: popMax,
      precipitationTypes: ptyCodes.map((c) => PTY_LABEL[c]),
      sky: SKY_LABEL[Number(noonSky?.fcstValue)] ?? null,
      dressSuggestion: Number.isFinite(tmn) ? `${dressFor(tmn)}${Number.isFinite(tmx) && tmx - tmn >= 8 ? ' (일교차 큼 — 레이어드 필수)' : ''}` : null,
      rainGearNeeded: (popMax ?? 0) >= 60,
    };
  });

  return {
    region: grid.label,
    basedOn: `기상청 단기예보 ${fmtDate(base)} ${baseTime} 발표분`,
    days: dailies,
    dressGuide: DRESS_GUIDE,
    note: '옷차림 제안은 일최저기온 기준(외출 시간대가 낮이면 최고기온 밴드도 참고). 예보는 발표 시점 기준이며 변경될 수 있습니다.',
  };
}
