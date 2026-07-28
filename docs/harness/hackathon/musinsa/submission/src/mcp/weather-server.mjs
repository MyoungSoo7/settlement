#!/usr/bin/env node
/**
 * fashion-first Weather MCP server (stdio, zero-dependency).
 *
 * 날씨×코디 축 — "이번 주(말) 뭐 입지", "내일 트렌치 입어도 돼?" 에
 * 기상청 단기예보 실데이터로 답한다. 기온 밴드→옷차림 가이드를 결과에 동봉해
 * 에이전트가 근거를 인용할 수 있게 한다.
 *
 * env: DATA_GO_KR_API_KEY (없으면 상위 .env 폴백 — kma/weather.mjs 참조)
 */
import { REGIONS, currentWeather, hasKey, outfitBrief } from '../kma/weather.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'fashion-first-weather';
const SERVER_VERSION = '0.1.0';

const TOOLS = [
  {
    name: 'weather_now',
    description: '현재 날씨 실황(기온·강수·습도·바람) + 기온 밴드 기반 옷차림 제안. "지금 나가는데 뭐 입지"의 근거. region 은 주요 도시명(기본 서울), 목록 밖 지역은 nx/ny 격자 직접 지정.',
    inputSchema: {
      type: 'object',
      properties: {
        region: { type: 'string', description: `지역명 (지원: ${Object.keys(REGIONS).join(', ')} — 기본 서울)` },
        nx: { type: 'integer', description: '기상청 격자 X (region 대신 직접 지정 시)' },
        ny: { type: 'integer', description: '기상청 격자 Y (region 대신 직접 지정 시)' },
      },
    },
    run: ({ region, nx, ny }) => currentWeather({ region, nx, ny }),
  },
  {
    name: 'weather_outfit_brief',
    description: '오늘~3일 일별 코디 브리핑 — 최저/최고기온, 최대 강수확률, 강수형태, 하늘 상태, 기온 밴드별 옷차림 제안(일교차 8도 이상이면 레이어드 경고, 강수확률 60% 이상이면 우천 대비 플래그). "이번 주말 데이트 코디", "내일 출근룩" 판단의 근거.',
    inputSchema: {
      type: 'object',
      properties: {
        region: { type: 'string', description: '지역명 (기본 서울)' },
        days: { type: 'integer', description: '브리핑 일수 (1~3, 기본 3)' },
        nx: { type: 'integer', description: '기상청 격자 X (선택)' },
        ny: { type: 'integer', description: '기상청 격자 Y (선택)' },
      },
    },
    run: ({ region, days, nx, ny }) => outfitBrief({ region, days, nx, ny }),
  },
  {
    name: 'weather_status',
    description: '날씨 축 연결 상태 점검 — API 키 존재 여부, 지원 지역 목록을 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: hasKey() ? 'present' : 'missing (env DATA_GO_KR_API_KEY 또는 상위 .env — data.go.kr 에서 기상청_단기예보 활용신청)',
      backend: '기상청 단기예보 조회서비스 (공공데이터포털)',
      regions: Object.keys(REGIONS),
      tools: TOOLS.map((t) => t.name),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
